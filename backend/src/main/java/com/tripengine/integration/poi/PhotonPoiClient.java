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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Fetches real OpenStreetMap POIs (restaurants, cafes, parks, viewpoints, museums, historic sites)
 * via Komoot's fast ElasticSearch-backed Photon API using bounded bbox + osm_tag filtering.
 */
@Component
public class PhotonPoiClient {

    private static final Logger log = LoggerFactory.getLogger(PhotonPoiClient.class);
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private static final Set<String> GENERIC_NAMES = Set.of(
            "restaurant", "cafe", "café", "park", "garden", "hotel", "shop", "mall",
            "cafe shop", "coffee shop", "fast food", "dhaba", "bar", "pub", "viewpoint",
            "museum", "temple", "monument", "cafe1", "restaurant1", "food court"
    );

    private static final Set<String> EXCLUDED_OSM_VALUES = Set.of(
            "hotel", "hostel", "guest_house", "motel", "apartment", "chalet", "camp_site"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualThreadExecutor;

    public PhotonPoiClient(
            @Qualifier("fastExternalRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            ExecutorService virtualThreadExecutor) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    private record PhotonQuery(String q, String osmTag, int limit) {}

    public List<Poi> fetchPoisInBoundingBox(String tileKey, double south, double west, double north, double east) {
        String bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", west, south, east, north);

        // Keep to 5 targeted queries so we never trip Photon's burst rate-limit
        List<PhotonQuery> queries = List.of(
                new PhotonQuery("restaurant", "amenity:restaurant", 25),
                new PhotonQuery("cafe", "amenity:cafe", 20),
                new PhotonQuery("viewpoint", "tourism:viewpoint", 15),
                new PhotonQuery("museum", "tourism:museum", 15),
                new PhotonQuery("park", "leisure:park", 15)
        );

        List<CompletableFuture<List<Poi>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(
                        () -> executeSingleQuery(tileKey, bbox, q), virtualThreadExecutor))
                .toList();

        List<Poi> all = new ArrayList<>();
        for (CompletableFuture<List<Poi>> f : futures) {
            try {
                all.addAll(f.join());
            } catch (Exception ignored) {
                // Individual query failures shouldn't fail the batch
            }
        }
        log.info("Photon API returned {} raw POIs for bbox {}", all.size(), bbox);
        return all;
    }

    private List<Poi> executeSingleQuery(String tileKey, String bbox, PhotonQuery query) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://photon.komoot.io/api/")
                    .queryParam("q", query.q())
                    .queryParam("bbox", bbox)
                    .queryParam("osm_tag", query.osmTag())
                    .queryParam("limit", query.limit())
                    .build()
                    .encode()
                    .toUri();

            byte[] raw = restClient.get().uri(uri).retrieve().body(byte[].class);
            if (raw == null || raw.length == 0) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(raw);
            JsonNode features = root.path("features");
            if (!features.isArray()) {
                return List.of();
            }

            List<Poi> pois = new ArrayList<>();
            for (JsonNode feature : features) {
                JsonNode props = feature.path("properties");
                JsonNode coords = feature.path("geometry").path("coordinates");
                if (!coords.isArray() || coords.size() < 2) continue;

                String name = props.path("name").asText("").trim();
                if (!isValidName(name)) continue;

                String osmKey = props.path("osm_key").asText("");
                String osmValue = props.path("osm_value").asText("");
                if (EXCLUDED_OSM_VALUES.contains(osmValue)) continue;

                double lng = coords.get(0).asDouble();
                double lat = coords.get(1).asDouble();
                String osmType = props.path("osm_type").asText("N");
                String osmId = props.path("osm_id").asText(String.valueOf(name.hashCode()));

                PoiCategory category = classify(osmKey, osmValue, name);
                short priceTier = estimatePriceTier(category, name);

                Poi poi = new Poi();
                poi.setExternalId("osm:" + osmType + ":" + osmId);
                poi.setTileKey(tileKey);
                poi.setName(name);
                poi.setCategory(category);
                poi.setPriceTier(priceTier);
                poi.setEstimatedCost(estimateCostInr(category, priceTier));
                poi.setRating(estimateRating(category, props));
                poi.setAvgDurationMins(estimateDurationMins(category));

                Map<String, String> tags = new HashMap<>();
                tags.put("source", "photon-osm");
                if (!osmKey.isEmpty()) tags.put(osmKey, osmValue);
                if (props.has("street")) tags.put("street", props.get("street").asText());
                if (props.has("locality")) tags.put("locality", props.get("locality").asText());
                if (props.has("district")) tags.put("district", props.get("district").asText());
                poi.setTags(tags);
                poi.setOpeningHours(Map.of());
                poi.setHoursEstimated(true);

                Point pt = GF.createPoint(new Coordinate(lng, lat));
                pt.setSRID(4326);
                poi.setLocation(pt);
                poi.setUpdatedAt(Instant.now());
                pois.add(poi);
            }
            return pois;
        } catch (Exception ex) {
            log.debug("Photon query '{}' ({}) failed: {}", query.q(), query.osmTag(), ex.getMessage());
            return List.of();
        }
    }

    private boolean isValidName(String name) {
        if (name.length() < 3) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (GENERIC_NAMES.contains(lower)) return false;
        if (lower.contains("wifi") || lower.contains("atm") || lower.contains("parking")) return false;
        return true;
    }

    private PoiCategory classify(String osmKey, String osmValue, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (osmKey.equals("amenity")) {
            if (osmValue.equals("cafe")) return PoiCategory.CAFE;
            if (osmValue.equals("bar") || osmValue.equals("pub")) return PoiCategory.NIGHTLIFE;
            return PoiCategory.RESTAURANT;
        }
        if (osmKey.equals("historic") || osmValue.equals("museum") || osmValue.equals("gallery")) {
            return PoiCategory.CULTURE;
        }
        if (osmKey.equals("leisure") || osmKey.equals("natural")) {
            if (lower.contains("adventure") || lower.contains("water") || lower.contains("trek")) {
                return PoiCategory.ADVENTURE;
            }
            return PoiCategory.NATURE;
        }
        if (osmKey.equals("shop")) return PoiCategory.SHOPPING;
        if (osmValue.equals("viewpoint")) return PoiCategory.ADVENTURE;
        return PoiCategory.ATTRACTION;
    }

    private short estimatePriceTier(PoiCategory category, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (category == PoiCategory.NATURE) return 1;
        if (lower.contains("dhaba") || lower.contains("tiffin") || lower.contains("street") || lower.contains("sweets")) {
            return 1;
        }
        if (lower.contains("bistro") || lower.contains("lounge") || lower.contains("grill") || lower.contains("fine")) {
            return 3;
        }
        return 2;
    }

    private int estimateCostInr(PoiCategory category, short priceTier) {
        return switch (category) {
            case NATURE -> 50;
            case CAFE -> 220 * priceTier;
            case RESTAURANT -> 320 * priceTier;
            case NIGHTLIFE -> 600 * priceTier;
            case ADVENTURE -> 400 * priceTier;
            case SHOPPING -> 350 * priceTier;
            case CULTURE -> 200 * priceTier;
            case ATTRACTION -> 200 * priceTier;
        };
    }

    private int estimateDurationMins(PoiCategory category) {
        return switch (category) {
            case CAFE -> 45;
            case RESTAURANT -> 60;
            case NIGHTLIFE -> 90;
            case ADVENTURE -> 105;
            case CULTURE, ATTRACTION -> 90;
            case NATURE -> 75;
            case SHOPPING -> 60;
        };
    }

    private double estimateRating(PoiCategory category, JsonNode props) {
        double base = category.isFood() ? 4.2 : 4.3;
        if (props.has("street") && props.has("locality")) base += 0.2;
        return Math.min(4.8, base);
    }
}
