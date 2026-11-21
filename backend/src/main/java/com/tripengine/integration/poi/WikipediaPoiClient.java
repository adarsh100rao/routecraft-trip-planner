package com.tripengine.integration.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.PoiCategory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Fetches notable real-world landmarks, temples, forts, museums, parks, lakes, and valleys
 * via Wikipedia's GeoSearch API (prop=coordinates|description).
 */
@Component
public class WikipediaPoiClient {

    private static final Logger log = LoggerFactory.getLogger(WikipediaPoiClient.class);
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private static final List<String> EXCLUDE_TERMS = List.of(
            "metro station", "railway station", "train station", "bus station", "airport",
            "constituency", "university", "college", "school", "institute of technology",
            "hospital", "medical", "diocese", "district", "tehsil", "municipality",
            "legislative assembly", "parliament", "former province", "highway", "road",
            "company", "bank", "headquarters", "regulatory", "suburb", "neighbourhood",
            "neighborhood", "residential", "locality", "village in", "town in", "city in"
    );

    private static final List<String> INCLUDE_TERMS = List.of(
            "temple", "fort", "palace", "museum", "park", "lake", "monument", "mosque",
            "church", "cathedral", "garden", "waterfall", "sanctuary", "market", "bazaar",
            "zoo", "mall", "tower", "gate", "memorial", "gallery", "hill", "peak", "valley",
            "dam", "bridge", "monastery", "hot spring", "beach", "cave", "tomb", "imambara",
            "residency", "planetarium", "observatory", "attraction", "historic", "heritage",
            "landmark", "shrine", "mountaineering", "adventure", "aquarium", "botanical",
            "fortress", "citadel", "ruins", "ghat", "stepwell", "baoli", "haveli", "ashram"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final java.util.concurrent.ExecutorService virtualThreadExecutor;

    public WikipediaPoiClient(
            @Qualifier("fastExternalRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            java.util.concurrent.ExecutorService virtualThreadExecutor) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    public List<Poi> fetchLandmarks(String tileKey, double centerLat, double centerLng) {
        // Fan out across 5 points (center + 4 quadrants ~3.8km offset) on Virtual Threads
        // so large metros don't saturate the 50-result distance cap on downtown suburbs/stations
        double offset = 0.035;
        double[][] probePoints = {
                {centerLat, centerLng},
                {centerLat + offset, centerLng + offset},
                {centerLat - offset, centerLng - offset},
                {centerLat + offset, centerLng - offset},
                {centerLat - offset, centerLng + offset}
        };

        List<java.util.concurrent.CompletableFuture<List<Poi>>> futures = Arrays.stream(probePoints)
                .map(pt -> java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> fetchAroundPoint(tileKey, pt[0], pt[1]), virtualThreadExecutor))
                .toList();

        Map<String, Poi> uniqueByExtId = new LinkedHashMap<>();
        for (var f : futures) {
            try {
                for (Poi p : f.join()) {
                    uniqueByExtId.putIfAbsent(p.getExternalId(), p);
                }
            } catch (Exception ignored) {
                // Individual probe failure shouldn't drop other quadrants
            }
        }
        List<Poi> results = new ArrayList<>(uniqueByExtId.values());
        log.info("Wikipedia 5-point GeoSearch returned {} curated landmarks near ({}, {})",
                results.size(), centerLat, centerLng);
        return results;
    }

    private List<Poi> fetchAroundPoint(String tileKey, double centerLat, double centerLng) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://en.wikipedia.org/w/api.php")
                    .queryParam("action", "query")
                    .queryParam("generator", "geosearch")
                    .queryParam("ggscoord", String.format(Locale.US, "%.5f|%.5f", centerLat, centerLng))
                    .queryParam("ggsradius", 10000)
                    .queryParam("ggslimit", 50)
                    .queryParam("prop", "coordinates|description")
                    .queryParam("format", "json")
                    .build()
                    .encode()
                    .toUri();

            byte[] raw = restClient.get().uri(uri).retrieve().body(byte[].class);
            if (raw == null || raw.length == 0) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(raw);
            JsonNode pages = root.path("query").path("pages");
            if (!pages.isObject()) {
                return List.of();
            }

            List<Poi> results = new ArrayList<>();
            pages.fields().forEachRemaining(entry -> {
                JsonNode page = entry.getValue();
                String title = page.path("title").asText("").trim();
                String desc = page.path("description").asText("").trim();
                JsonNode coords = page.path("coordinates");

                if (title.isEmpty() || !coords.isArray() || coords.isEmpty()) {
                    return;
                }
                if (!isTouristLandmark(title, desc)) {
                    return;
                }

                double lat = coords.get(0).path("lat").asDouble();
                double lng = coords.get(0).path("lon").asDouble();
                String pageId = page.path("pageid").asText(entry.getKey());

                PoiCategory category = classify(title, desc);
                short priceTier = (category == PoiCategory.NATURE || desc.toLowerCase(Locale.ROOT).contains("temple"))
                        ? (short) 1 : (short) 2;

                Poi poi = new Poi();
                poi.setExternalId("wiki:" + pageId);
                poi.setTileKey(tileKey);
                poi.setName(title);
                poi.setCategory(category);
                poi.setPriceTier(priceTier);
                poi.setEstimatedCost(estimateCost(category, priceTier));
                poi.setRating(4.6); // Wikipedia-notable landmarks carry high visitor significance
                poi.setAvgDurationMins(category == PoiCategory.ADVENTURE ? 120 : 90);
                Map<String, String> tags = new HashMap<>();
                tags.put("source", "wikipedia");
                if (!desc.isEmpty()) tags.put("description", desc);
                tags.put("wikipedia", title);
                poi.setTags(tags);
                poi.setOpeningHours(Map.of());
                poi.setHoursEstimated(true);

                Point pt = GF.createPoint(new Coordinate(lng, lat));
                pt.setSRID(4326);
                poi.setLocation(pt);
                poi.setUpdatedAt(Instant.now());
                results.add(poi);
            });

            return results;
        } catch (Exception ex) {
            log.debug("Wikipedia GeoSearch probe failed near ({}, {}): {}", centerLat, centerLng, ex.getMessage());
            return List.of();
        }
    }

    private boolean isTouristLandmark(String title, String desc) {
        String combined = (title + " " + desc).toLowerCase(Locale.ROOT);
        for (String bad : EXCLUDE_TERMS) {
            if (combined.contains(bad)) return false;
        }
        for (String good : INCLUDE_TERMS) {
            if (combined.contains(good)) return true;
        }
        return false;
    }

    private PoiCategory classify(String title, String desc) {
        String text = (title + " " + desc).toLowerCase(Locale.ROOT);
        if (containsAny(text, "waterfall", "peak", "valley", "mountaineering", "adventure", "cave", "trek")) {
            return PoiCategory.ADVENTURE;
        }
        if (containsAny(text, "park", "garden", "lake", "sanctuary", "botanical", "zoo", "beach", "hot spring", "hill")) {
            return PoiCategory.NATURE;
        }
        if (containsAny(text, "temple", "fort", "palace", "museum", "monument", "mosque", "church",
                "cathedral", "memorial", "tomb", "imambara", "residency", "monastery", "historic", "heritage", "ruins", "shrine")) {
            return PoiCategory.CULTURE;
        }
        if (containsAny(text, "market", "bazaar", "mall")) {
            return PoiCategory.SHOPPING;
        }
        return PoiCategory.ATTRACTION;
    }

    private int estimateCost(PoiCategory category, short priceTier) {
        return switch (category) {
            case NATURE -> 50;
            case CULTURE -> priceTier == 1 ? 50 : 250;
            case ADVENTURE -> 500;
            case SHOPPING -> 400;
            default -> 200;
        };
    }

    private boolean containsAny(String text, String... terms) {
        for (String t : terms) {
            if (text.contains(t)) return true;
        }
        return false;
    }
}
