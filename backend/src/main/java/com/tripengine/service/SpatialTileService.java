package com.tripengine.service;

import com.tripengine.config.TripEngineProperties;
import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.entity.SpatialTile;
import com.tripengine.domain.model.GeoPoint;
import com.tripengine.integration.poi.PoiProviderClient;
import com.tripengine.repository.PoiRepository;
import com.tripengine.repository.SpatialTileRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SpatialTileService {

    private static final Logger log = LoggerFactory.getLogger(SpatialTileService.class);
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final SpatialTileRepository tileRepository;
    private final PoiRepository poiRepository;
    private final PoiProviderClient poiProviderClient;
    private final TripEngineProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, ReentrantLock> tileLocks = new ConcurrentHashMap<>();

    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Timer hitTimer;
    private final Timer missTimer;

    public SpatialTileService(
            SpatialTileRepository tileRepository,
            PoiRepository poiRepository,
            PoiProviderClient poiProviderClient,
            TripEngineProperties properties,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry) {
        this.tileRepository = tileRepository;
        this.poiRepository = poiRepository;
        this.poiProviderClient = poiProviderClient;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.cacheHitCounter = Counter.builder("trip.tile_cache.hits").register(meterRegistry);
        this.cacheMissCounter = Counter.builder("trip.tile_cache.misses").register(meterRegistry);
        this.hitTimer = Timer.builder("trip.poi_fetch.duration").tag("cache", "hit").register(meterRegistry);
        this.missTimer = Timer.builder("trip.poi_fetch.duration").tag("cache", "miss").register(meterRegistry);
    }

    public record TileFetchResult(List<Poi> pois, boolean cacheHit, long fetchDurationMs) {}

    public TileFetchResult getPoisNear(String destination, GeoPoint center) {
        long startNanos = System.nanoTime();
        double grid = properties.tile().gridSizeDegrees();
        Instant freshnessThreshold = Instant.now().minus(properties.tile().ttlDays(), ChronoUnit.DAYS);

        // Compute 3x3 covering spatial tiles around destination center
        List<TileSpec> coveringTiles = computeCoveringTiles(center, grid);
        List<String> keys = coveringTiles.stream().map(TileSpec::key).toList();

        Set<String> freshKeys = new HashSet<>();
        tileRepository.findByTileKeyInAndLastFetchedAtAfter(keys, freshnessThreshold)
                .forEach(t -> freshKeys.add(t.getTileKey()));

        List<TileSpec> missingTiles = coveringTiles.stream()
                .filter(t -> !freshKeys.contains(t.key()))
                .toList();

        boolean fullCacheHit = missingTiles.isEmpty();
        if (fullCacheHit) {
            cacheHitCounter.increment();
        } else {
            cacheMissCounter.increment();
            fetchAndPersistMissingTiles(destination, center, missingTiles, freshnessThreshold);
        }

        double radius = properties.tile().defaultSearchRadiusMeters();
        List<Poi> pois = poiRepository.findWithinRadius(center.lat(), center.lng(), radius, 300);

        if (pois.size() < 15) {
            log.info("Sparse POI result ({} found within {}m), expanding radius to {}m",
                    pois.size(), radius, properties.tile().expandedSearchRadiusMeters());
            pois = poiRepository.findWithinRadius(
                    center.lat(), center.lng(), properties.tile().expandedSearchRadiusMeters(), 300);
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        (fullCacheHit ? hitTimer : missTimer).record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        return new TileFetchResult(pois, fullCacheHit, elapsedNanos / 1_000_000);
    }

    private void fetchAndPersistMissingTiles(String destination, GeoPoint center, List<TileSpec> missingTiles, Instant freshnessThreshold) {
        // Merge missing tiles into a single bounding box query to avoid external API rate limits,
        // while locking on the center tile key to prevent concurrent cache stampedes.
        String lockKey = missingTiles.get(0).key();
        ReentrantLock lock = tileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check after acquiring lock
            List<String> keys = missingTiles.stream().map(TileSpec::key).toList();
            Set<String> nowFresh = new HashSet<>();
            tileRepository.findByTileKeyInAndLastFetchedAtAfter(keys, freshnessThreshold)
                    .forEach(t -> nowFresh.add(t.getTileKey()));

            List<TileSpec> stillMissing = missingTiles.stream()
                    .filter(t -> !nowFresh.contains(t.key()))
                    .toList();
            if (stillMissing.isEmpty()) {
                return;
            }

            double south = stillMissing.stream().mapToDouble(TileSpec::south).min().orElseThrow();
            double west = stillMissing.stream().mapToDouble(TileSpec::west).min().orElseThrow();
            double north = stillMissing.stream().mapToDouble(TileSpec::north).max().orElseThrow();
            double east = stillMissing.stream().mapToDouble(TileSpec::east).max().orElseThrow();

            String primaryTileKey = stillMissing.get(stillMissing.size() / 2).key();
            List<Poi> fetchedPois = poiProviderClient.fetchPoisInBoundingBox(primaryTileKey, destination, center, south, west, north, east);

            if (fetchedPois.isEmpty()) {
                log.warn("No POIs returned for missing tiles around {} — skipping tile cache write to avoid negative-cache poisoning", primaryTileKey);
                return;
            }

            transactionTemplate.executeWithoutResult(status -> {
                Instant now = Instant.now();
                double grid = properties.tile().gridSizeDegrees();
                Set<String> validKeys = new HashSet<>(keys);
                Map<String, Integer> countByTile = new HashMap<>();

                for (Poi incoming : fetchedPois) {
                    String assignedKey = tileKeyFor(incoming.getLat(), incoming.getLng(), grid);
                    if (!validKeys.contains(assignedKey)) {
                        assignedKey = primaryTileKey;
                    }
                    incoming.setTileKey(assignedKey);
                    countByTile.merge(assignedKey, 1, Integer::sum);
                }

                for (TileSpec spec : stillMissing) {
                    SpatialTile tile = new SpatialTile(
                            spec.key(), createPolygon(spec), now, countByTile.getOrDefault(spec.key(), 0));
                    tileRepository.save(tile);
                }

                List<String> extIds = fetchedPois.stream().map(Poi::getExternalId).toList();
                Map<String, Poi> existingByExtId = new HashMap<>();
                poiRepository.findByExternalIdIn(extIds)
                        .forEach(p -> existingByExtId.put(p.getExternalId(), p));

                List<Poi> toSave = new ArrayList<>();
                for (Poi incoming : fetchedPois) {
                    Poi existing = existingByExtId.get(incoming.getExternalId());
                    if (existing != null) {
                        existing.setName(incoming.getName());
                        existing.setCategory(incoming.getCategory());
                        existing.setPriceTier(incoming.getPriceTier());
                        existing.setEstimatedCost(incoming.getEstimatedCost());
                        existing.setRating(incoming.getRating());
                        existing.setTags(incoming.getTags());
                        existing.setOpeningHours(incoming.getOpeningHours());
                        existing.setHoursEstimated(incoming.isHoursEstimated());
                        existing.setUpdatedAt(now);
                        toSave.add(existing);
                    } else {
                        toSave.add(incoming);
                    }
                }
                poiRepository.saveAll(toSave);
            });
        } finally {
            lock.unlock();
        }
    }

    public List<TileSpec> computeCoveringTiles(GeoPoint center, double grid) {
        double baseLat = Math.floor(center.lat() / grid) * grid;
        double baseLng = Math.floor(center.lng() / grid) * grid;
        List<TileSpec> specs = new ArrayList<>(9);
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLng = -1; dLng <= 1; dLng++) {
                double s = roundCoord(baseLat + dLat * grid);
                double w = roundCoord(baseLng + dLng * grid);
                double n = roundCoord(s + grid);
                double e = roundCoord(w + grid);
                String key = String.format(Locale.US, "tile_%.2f_%.2f", s, w);
                specs.add(new TileSpec(key, s, w, n, e));
            }
        }
        return specs;
    }

    private String tileKeyFor(double lat, double lng, double grid) {
        double s = roundCoord(Math.floor(lat / grid) * grid);
        double w = roundCoord(Math.floor(lng / grid) * grid);
        return String.format(Locale.US, "tile_%.2f_%.2f", s, w);
    }

    private double roundCoord(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private Polygon createPolygon(TileSpec s) {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(s.west(), s.south()),
                new Coordinate(s.east(), s.south()),
                new Coordinate(s.east(), s.north()),
                new Coordinate(s.west(), s.north()),
                new Coordinate(s.west(), s.south())
        };
        Polygon poly = GEOMETRY_FACTORY.createPolygon(coords);
        poly.setSRID(4326);
        return poly;
    }

    public record TileSpec(String key, double south, double west, double north, double east) {}
}
