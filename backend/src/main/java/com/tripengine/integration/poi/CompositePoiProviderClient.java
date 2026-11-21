package com.tripengine.integration.poi;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.model.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Primary POI Aggregator:
 * 1. GeminiCuratedPoiClient (community/Reddit-style iconic attractions, cafes & restaurants,
 *    grounded against OpenStreetMap coordinates via Photon)
 * 2. Nominatim Bounded Viewbox + Photon OSM (verified OSM fallback & extra local venues)
 */
@Primary
@Component
public class CompositePoiProviderClient implements PoiProviderClient {

    private static final Logger log = LoggerFactory.getLogger(CompositePoiProviderClient.class);

    private final GeminiCuratedPoiClient geminiClient;
    private final PhotonPoiClient photonClient;
    private final NominatimPoiClient nominatimClient;
    private final ExecutorService virtualThreadExecutor;

    public CompositePoiProviderClient(
            GeminiCuratedPoiClient geminiClient,
            PhotonPoiClient photonClient,
            NominatimPoiClient nominatimClient,
            ExecutorService virtualThreadExecutor) {
        this.geminiClient = geminiClient;
        this.photonClient = photonClient;
        this.nominatimClient = nominatimClient;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public List<Poi> fetchPoisInBoundingBox(
            String tileKey, String destination, GeoPoint center,
            double south, double west, double north, double east) {

        List<Poi> combined = new ArrayList<>();

        if (geminiClient.isConfigured()) {
            // Run Gemini community curation (with OSM coordinate grounding) + Nominatim in parallel
            CompletableFuture<List<Poi>> curatedFuture = CompletableFuture.supplyAsync(
                    () -> geminiClient.fetchCuratedAndGroundedPois(
                            tileKey, destination, center, south, west, north, east),
                    virtualThreadExecutor);
            CompletableFuture<List<Poi>> nominatimFuture = CompletableFuture.supplyAsync(
                    () -> nominatimClient.fetchBoundedPois(tileKey, south, west, north, east),
                    virtualThreadExecutor);

            List<Poi> curated = safeJoin(curatedFuture);
            combined.addAll(curated);

            // Supplement with verified OSM venues (lower priority than community-curated picks)
            if (curated.size() < 15) {
                combined.addAll(safeJoin(nominatimFuture));
                combined.addAll(photonClient.fetchPoisInBoundingBox(tileKey, south, west, north, east));
            } else {
                // Still add Nominatim venues as extra dining/park options for Lock & Re-optimize
                List<Poi> osmExtras = safeJoin(nominatimFuture);
                for (Poi p : osmExtras) {
                    p.setRating(Math.min(p.getRating(), 4.1)); // Keep community-curated picks ranked above raw OSM
                }
                combined.addAll(osmExtras);
            }
        } else {
            CompletableFuture<List<Poi>> photonFuture = CompletableFuture.supplyAsync(
                    () -> photonClient.fetchPoisInBoundingBox(tileKey, south, west, north, east),
                    virtualThreadExecutor);
            CompletableFuture<List<Poi>> nominatimFuture = CompletableFuture.supplyAsync(
                    () -> nominatimClient.fetchBoundedPois(tileKey, south, west, north, east),
                    virtualThreadExecutor);
            combined.addAll(safeJoin(nominatimFuture));
            combined.addAll(safeJoin(photonFuture));
        }

        List<Poi> deduplicated = deduplicate(combined);
        log.info("Composite POI aggregator resolved {} unique POIs (from {} raw) for '{}' (tile={})",
                deduplicated.size(), combined.size(), destination, tileKey);
        return deduplicated;
    }

    private List<Poi> safeJoin(CompletableFuture<List<Poi>> future) {
        try {
            return future.join();
        } catch (Exception ex) {
            log.warn("One POI provider failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<Poi> deduplicate(List<Poi> raw) {
        Map<String, Poi> byExternalId = new LinkedHashMap<>();
        Map<String, Poi> byNormalizedName = new LinkedHashMap<>();
        List<Poi> accepted = new ArrayList<>();

        for (Poi poi : raw) {
            if (byExternalId.containsKey(poi.getExternalId())) continue;
            String normName = normalizeName(poi.getName());
            if (normName.isEmpty()) continue;

            Poi existingByName = byNormalizedName.get(normName);
            if (existingByName != null) {
                mergeMetadata(existingByName, poi);
                continue;
            }

            boolean nearDuplicate = false;
            GeoPoint pt = new GeoPoint(poi.getLat(), poi.getLng());
            for (Poi kept : accepted) {
                if (kept.getCategory() == poi.getCategory()
                        && pt.haversineKm(new GeoPoint(kept.getLat(), kept.getLng())) < 0.10
                        && sharesSignificantWord(normName, normalizeName(kept.getName()))) {
                    mergeMetadata(kept, poi);
                    nearDuplicate = true;
                    break;
                }
            }
            if (nearDuplicate) continue;

            byExternalId.put(poi.getExternalId(), poi);
            byNormalizedName.put(normName, poi);
            accepted.add(poi);
        }
        return accepted;
    }

    private void mergeMetadata(Poi target, Poi donor) {
        if (target.isHoursEstimated() && !donor.isHoursEstimated() && !donor.getOpeningHours().isEmpty()) {
            target.setOpeningHours(donor.getOpeningHours());
            target.setHoursEstimated(false);
        }
        if (donor.getTags() != null && target.getTags() != null) {
            donor.getTags().forEach(target.getTags()::putIfAbsent);
        }
    }

    private String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean sharesSignificantWord(String a, String b) {
        Set<String> stop = Set.of("the", "and", "of", "in", "cafe", "restaurant", "park", "temple", "hotel");
        Set<String> wordsA = new HashSet<>(Arrays.asList(a.split(" ")));
        wordsA.removeAll(stop);
        for (String w : b.split(" ")) {
            if (w.length() >= 4 && wordsA.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
