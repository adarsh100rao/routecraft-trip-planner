package com.tripengine.service;

import com.tripengine.domain.entity.ItineraryStop;
import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.entity.Trip;
import com.tripengine.domain.enums.InterestTag;
import com.tripengine.domain.model.*;
import com.tripengine.dto.request.GenerateTripRequest;
import com.tripengine.dto.request.ReoptimizeDayRequest;
import com.tripengine.dto.request.UpdateStopRequest;
import com.tripengine.dto.response.DayPlanResponse;
import com.tripengine.dto.response.SseProgressEvent;
import com.tripengine.dto.response.StopResponse;
import com.tripengine.dto.response.TripResponse;
import com.tripengine.integration.geocoding.GeocodingClient;
import com.tripengine.integration.routing.RoutingClient;
import com.tripengine.repository.ItineraryStopRepository;
import com.tripengine.repository.TripRepository;
import com.tripengine.service.clustering.DayClusterer;
import com.tripengine.service.optimizer.ItineraryOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
public class TripGenerationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TripGenerationOrchestrator.class);

    private final GeocodingClient geocodingClient;
    private final SpatialTileService spatialTileService;
    private final PoiScoringService poiScoringService;
    private final DayClusterer dayClusterer;
    private final RoutingClient routingClient;
    private final ItineraryOptimizer itineraryOptimizer;
    private final TripRepository tripRepository;
    private final ItineraryStopRepository stopRepository;
    private final TripMapper tripMapper;
    private final SseJobRegistry sseJobRegistry;
    private final ExecutorService virtualThreadExecutor;
    private final TransactionTemplate transactionTemplate;

    public TripGenerationOrchestrator(
            GeocodingClient geocodingClient,
            SpatialTileService spatialTileService,
            PoiScoringService poiScoringService,
            DayClusterer dayClusterer,
            RoutingClient routingClient,
            ItineraryOptimizer itineraryOptimizer,
            TripRepository tripRepository,
            ItineraryStopRepository stopRepository,
            TripMapper tripMapper,
            SseJobRegistry sseJobRegistry,
            ExecutorService virtualThreadExecutor,
            TransactionTemplate transactionTemplate) {
        this.geocodingClient = geocodingClient;
        this.spatialTileService = spatialTileService;
        this.poiScoringService = poiScoringService;
        this.dayClusterer = dayClusterer;
        this.routingClient = routingClient;
        this.itineraryOptimizer = itineraryOptimizer;
        this.tripRepository = tripRepository;
        this.stopRepository = stopRepository;
        this.tripMapper = tripMapper;
        this.sseJobRegistry = sseJobRegistry;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    public String submitGenerationJob(GenerateTripRequest request) {
        String jobId = UUID.randomUUID().toString();
        sseJobRegistry.registerJob(jobId);
        virtualThreadExecutor.submit(() -> runPipeline(jobId, request));
        return jobId;
    }

    private void runPipeline(String jobId, GenerateTripRequest request) {
        long pipelineStart = System.currentTimeMillis();
        try {
            // Stage 1: Geocode destination
            sseJobRegistry.emit(jobId, "STAGE_UPDATE",
                    SseProgressEvent.of("GEOCODING", "Resolving destination coordinates for " + request.destination(), 12));
            GeocodingClient.GeocodedLocation loc = geocodingClient.geocode(request.destination());

            // Stage 2: Spatial Tile Cache lookup / Overpass fetch
            sseJobRegistry.emit(jobId, "STAGE_UPDATE",
                    SseProgressEvent.of("FETCHING_POIS", "Querying PostGIS spatial tile cache...", 28));
            SpatialTileService.TileFetchResult tileResult = spatialTileService.getPoisNear(request.destination(), loc.center());

            if (tileResult.pois().isEmpty()) {
                sseJobRegistry.fail(jobId, "No places found near " + request.destination() + ". Try a larger city.");
                return;
            }

            String cacheLabel = tileResult.cacheHit() ? "HIT" : "MISS";
            sseJobRegistry.emit(jobId, "STAGE_UPDATE", SseProgressEvent.of(
                    "POIS_LOADED",
                    "Loaded %d verified places (spatial cache %s in %dms)".formatted(
                            tileResult.pois().size(), cacheLabel, tileResult.fetchDurationMs()),
                    48,
                    Map.of("cacheHit", tileResult.cacheHit(), "poiCount", tileResult.pois().size(),
                            "fetchMs", tileResult.fetchDurationMs())
            ));

            // Stage 3: Score & Geo-Cluster into N compact daily zones
            sseJobRegistry.emit(jobId, "STAGE_UPDATE",
                    SseProgressEvent.of("CLUSTERING", "Scoring preferences & geo-clustering into " + request.days() + " daily zones", 62));

            List<ScoredPoi> scored = poiScoringService.scoreAndSelectCandidates(
                    tileResult.pois(), request.interests(), request.days(),
                    request.peopleCount(), request.totalBudget());

            List<DayCluster> clusters = dayClusterer.cluster(scored, request.days());

            // Stage 4: Parallel OSRM travel-time matrices + Time-Window/Budget Optimization
            sseJobRegistry.emit(jobId, "STAGE_UPDATE",
                    SseProgressEvent.of("OPTIMIZING", "Computing OSRM travel matrices & optimizing time-windows", 78));

            LocalDate startDate = request.effectiveStartDate();
            int dailyBudget = request.totalBudget() / request.days();

            record DayResult(int dayNumber, List<PlannedStop> stops, String polyline, String routingMode) {}

            List<CompletableFuture<DayResult>> futures = clusters.stream()
                    .map(cluster -> CompletableFuture.supplyAsync(() -> {
                        List<ScoredPoi> topActivities = cluster.candidates().stream()
                                .filter(c -> !c.poi().getCategory().isFood())
                                .sorted(Comparator.comparingDouble(ScoredPoi::score).reversed())
                                .limit(18)
                                .toList();
                        List<ScoredPoi> topFood = cluster.candidates().stream()
                                .filter(c -> c.poi().getCategory().isFood())
                                .sorted(Comparator.comparingDouble(ScoredPoi::score).reversed())
                                .limit(12)
                                .toList();
                        List<ScoredPoi> topClusterPois = new ArrayList<>(topActivities.size() + topFood.size());
                        topClusterPois.addAll(topActivities);
                        topClusterPois.addAll(topFood);
                        List<Poi> rawPois = topClusterPois.stream().map(ScoredPoi::poi).toList();
                        TravelMatrix matrix = routingClient.getTravelTimeMatrix(rawPois);

                        DayOfWeek dow = startDate.plusDays(cluster.dayNumber() - 1L).getDayOfWeek();
                        DayConstraints constraints = new DayConstraints(
                                cluster.dayNumber(), dow, request.effectivePace(),
                                request.peopleCount(), dailyBudget
                        );

                        List<PlannedStop> planned = itineraryOptimizer.optimizeDay(
                                topClusterPois, matrix, constraints, List.of());

                        List<GeoPoint> routePoints = planned.stream()
                                .map(s -> new GeoPoint(s.poi().getLat(), s.poi().getLng()))
                                .toList();
                        String polyline = routingClient.getRoutePolyline(routePoints);

                        return new DayResult(cluster.dayNumber(), planned, polyline, matrix.routingMode());
                    }, virtualThreadExecutor))
                    .toList();

            List<DayResult> dayResults = futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(DayResult::dayNumber))
                    .toList();

            // Stage 5: Persist Trip & Stops
            sseJobRegistry.emit(jobId, "STAGE_UPDATE",
                    SseProgressEvent.of("PERSISTING", "Saving optimized itinerary...", 92));

            long totalTimeMs = System.currentTimeMillis() - pipelineStart;

            TripResponse response = transactionTemplate.execute(status -> {
                Trip trip = new Trip();
                trip.setDestination(request.destination());
                trip.setCenterLat(loc.center().lat());
                trip.setCenterLng(loc.center().lng());
                trip.setStartDate(startDate);
                trip.setDays((short) request.days());
                trip.setPeopleCount((short) request.peopleCount());
                trip.setTotalBudget(request.totalBudget());
                trip.setCurrency(request.effectiveCurrency());
                trip.setPace(request.effectivePace());
                trip.setPreferences(request.interests().stream().map(Enum::name).toList());

                Map<String, String> polylines = new HashMap<>();
                int totalEstimatedCost = 0;
                String routingMode = "OSRM";

                for (DayResult dr : dayResults) {
                    polylines.put(String.valueOf(dr.dayNumber()), dr.polyline());
                    if (!"OSRM".equals(dr.routingMode())) {
                        routingMode = dr.routingMode();
                    }
                    int order = 0;
                    for (PlannedStop ps : dr.stops()) {
                        ItineraryStop stop = new ItineraryStop();
                        stop.setTrip(trip);
                        stop.setPoi(ps.poi());
                        stop.setDayNumber((short) dr.dayNumber());
                        stop.setOrderIndex((short) order++);
                        stop.setStartTime(ps.startTime());
                        stop.setEndTime(ps.endTime());
                        stop.setTravelMinsFromPrev(ps.travelMinsFromPrev());
                        stop.setEstimatedCost(ps.estimatedGroupCost());
                        stop.setLocked(ps.locked());
                        stop.setSlotType(ps.slotType());
                        trip.getStops().add(stop);
                        totalEstimatedCost += ps.estimatedGroupCost();
                    }
                }

                trip.setEstimatedTotalCost(totalEstimatedCost);
                trip.setRoutingMode(routingMode);
                trip.setRoutePolylines(polylines);

                Trip saved = tripRepository.save(trip);
                return tripMapper.toTripResponse(saved, tileResult.cacheHit(), totalTimeMs);
            });

            sseJobRegistry.complete(jobId, "COMPLETED", Map.of(
                    "tripId", response.tripId(),
                    "trip", response
            ));
        } catch (Exception e) {
            log.error("Trip generation pipeline failed for job {}", jobId, e);
            sseJobRegistry.fail(jobId, e.getMessage() != null ? e.getMessage() : "Trip generation failed");
        }
    }

    @Transactional(readOnly = true)
    public TripResponse getTrip(UUID tripId) {
        Trip trip = tripRepository.findWithStopsById(tripId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + tripId));
        return tripMapper.toTripResponse(trip, true, 0L);
    }

    @Transactional
    public StopResponse updateStop(UUID tripId, UUID stopId, UpdateStopRequest req) {
        ItineraryStop stop = stopRepository.findByIdAndTripId(stopId, tripId)
                .orElseThrow(() -> new NoSuchElementException("Stop not found: " + stopId));
        if (req.isLocked() != null) {
            stop.setLocked(req.isLocked());
        }
        return tripMapper.toStopResponse(stopRepository.save(stop));
    }

    @Transactional
    public void deleteStop(UUID tripId, UUID stopId) {
        Trip trip = tripRepository.findWithStopsById(tripId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + tripId));
        boolean removed = trip.getStops().removeIf(s -> s.getId().equals(stopId));
        if (!removed) {
            throw new NoSuchElementException("Stop not found: " + stopId);
        }
        trip.setEstimatedTotalCost(trip.getStops().stream().mapToInt(ItineraryStop::getEstimatedCost).sum());
        tripRepository.save(trip);
    }

    @Transactional
    public DayPlanResponse reoptimizeDay(UUID tripId, int dayNumber, ReoptimizeDayRequest request) {
        Trip trip = tripRepository.findWithStopsById(tripId)
                .orElseThrow(() -> new NoSuchElementException("Trip not found: " + tripId));

        if (dayNumber < 1 || dayNumber > trip.getDays()) {
            throw new IllegalArgumentException("Invalid dayNumber: " + dayNumber);
        }

        Set<UUID> excludedPois = new HashSet<>();
        if (request != null && request.excludePoiIds() != null) {
            excludedPois.addAll(request.excludePoiIds());
        }
        // Avoid duplicating POIs already scheduled on OTHER days
        for (ItineraryStop s : trip.getStops()) {
            if (s.getDayNumber() != dayNumber) {
                excludedPois.add(s.getPoi().getId());
            }
        }

        Set<UUID> lockedStopIds = new HashSet<>();
        if (request != null && request.lockedStopIds() != null) {
            lockedStopIds.addAll(request.lockedStopIds());
        }

        List<PlannedStop> lockedStops = new ArrayList<>();
        for (ItineraryStop s : trip.getStops()) {
            if (s.getDayNumber() == dayNumber && (s.isLocked() || lockedStopIds.contains(s.getId()))) {
                lockedStops.add(new PlannedStop(
                        s.getPoi(), s.getStartTime(), s.getEndTime(),
                        s.getTravelMinsFromPrev(), s.getEstimatedCost(), true, s.getSlotType()
                ));
            }
        }

        SpatialTileService.TileFetchResult tileResult =
                spatialTileService.getPoisNear(trip.getDestination(), new GeoPoint(trip.getCenterLat(), trip.getCenterLng()));

        List<InterestTag> interests = trip.getPreferences().stream()
                .map(InterestTag::valueOf)
                .toList();

        List<ScoredPoi> candidates = poiScoringService.scoreAndSelectCandidates(
                        tileResult.pois(), interests, trip.getDays(), trip.getPeopleCount(), trip.getTotalBudget())
                .stream()
                .filter(sp -> !excludedPois.contains(sp.poi().getId()))
                .limit(30)
                .toList();

        List<Poi> allPoisForMatrix = new ArrayList<>(candidates.stream().map(ScoredPoi::poi).toList());
        for (PlannedStop ls : lockedStops) {
            if (allPoisForMatrix.stream().noneMatch(p -> p.getId().equals(ls.poi().getId()))) {
                allPoisForMatrix.add(ls.poi());
            }
        }

        TravelMatrix matrix = routingClient.getTravelTimeMatrix(allPoisForMatrix);
        int dailyBudget = (request != null && request.dayBudgetOverride() != null && request.dayBudgetOverride() > 0)
                ? request.dayBudgetOverride()
                : trip.getTotalBudget() / trip.getDays();

        DayOfWeek dow = trip.getStartDate().plusDays(dayNumber - 1L).getDayOfWeek();
        DayConstraints constraints = new DayConstraints(
                dayNumber, dow, trip.getPace(), trip.getPeopleCount(), dailyBudget);

        List<PlannedStop> replanned = itineraryOptimizer.optimizeDay(candidates, matrix, constraints, lockedStops);

        // Replace stops for this day — flush deletes before inserts so (trip_id, day_number, order_index) UK never collides
        trip.getStops().removeIf(s -> s.getDayNumber() == dayNumber);
        tripRepository.saveAndFlush(trip);
        List<ItineraryStop> newDayStops = new ArrayList<>();
        int idx = 0;
        for (PlannedStop ps : replanned) {
            ItineraryStop entity = new ItineraryStop();
            entity.setTrip(trip);
            entity.setPoi(ps.poi());
            entity.setDayNumber((short) dayNumber);
            entity.setOrderIndex((short) idx++);
            entity.setStartTime(ps.startTime());
            entity.setEndTime(ps.endTime());
            entity.setTravelMinsFromPrev(ps.travelMinsFromPrev());
            entity.setEstimatedCost(ps.estimatedGroupCost());
            entity.setLocked(ps.locked());
            entity.setSlotType(ps.slotType());
            trip.getStops().add(entity);
            newDayStops.add(entity);
        }

        List<GeoPoint> routePoints = replanned.stream()
                .map(s -> new GeoPoint(s.poi().getLat(), s.poi().getLng()))
                .toList();
        String polyline = routingClient.getRoutePolyline(routePoints);

        Map<String, String> polylines = new HashMap<>(trip.getRoutePolylines());
        polylines.put(String.valueOf(dayNumber), polyline);
        trip.setRoutePolylines(polylines);
        trip.setEstimatedTotalCost(trip.getStops().stream().mapToInt(ItineraryStop::getEstimatedCost).sum());
        tripRepository.save(trip);

        return tripMapper.toDayPlanResponse(trip, dayNumber, newDayStops, polyline);
    }
}
