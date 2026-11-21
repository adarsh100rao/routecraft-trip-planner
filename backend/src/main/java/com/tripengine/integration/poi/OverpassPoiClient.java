package com.tripengine.integration.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripengine.config.TripEngineProperties;
import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.PoiCategory;
import com.tripengine.domain.model.GeoPoint;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OverpassPoiClient implements PoiProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassPoiClient.class);
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final Pattern SIMPLE_HOURS = Pattern.compile("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})");

    private final RestClient restClient;
    private final TripEngineProperties properties;
    private final ObjectMapper objectMapper;

    public OverpassPoiClient(
            @Qualifier("externalRestClient") RestClient restClient,
            TripEngineProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "overpass", fallbackMethod = "fallbackEmpty")
    public List<Poi> fetchPoisInBoundingBox(String tileKey, String destination, GeoPoint center, double south, double west, double north,
            double east) {
        String bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", south, west, north, east);
        // Query both nodes and ways (nw) with `out center` so polygon landmarks (forts,
        // temples, parks) are captured
        String query = """
                [out:json][timeout:14];
                (
                  nw["tourism"~"attraction|museum|viewpoint|gallery|theme_park|zoo|picnic_site"](%s);
                  nw["historic"~"monument|castle|fort|palace|ruins|memorial|archaeological_site"](%s);
                  nw["natural"~"waterfall|peak|hot_spring|beach|cave_entrance"](%s);
                  nw["leisure"~"park|garden|nature_reserve|water_park|sports_centre"](%s);
                  node["amenity"~"restaurant|cafe|bar|pub|fast_food"](%s);
                  nw["shop"~"mall|craft|souvenir|department_store"](%s);
                );
                out center 250;
                """.formatted(bbox, bbox, bbox, bbox, bbox, bbox);

        List<String> endpoints = List.of(
                properties.external().overpassBaseUrl(),
                "https://overpass.kumi.systems/api/interpreter",
                "https://lz4.overpass-api.de/api/interpreter");

        String formBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode root = null;
        Exception lastError = null;

        for (String endpoint : endpoints) {
            try {
                log.info("Fetching POIs from Overpass ({}) for tile {} (bbox={})", endpoint, tileKey, bbox);
                // Read raw bytes because Overpass mirrors frequently respond with
                // application/octet-stream
                byte[] rawBytes = restClient.post()
                        .uri(endpoint)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.ALL)
                        .body(formBody)
                        .retrieve()
                        .body(byte[].class);

                String rawJson = rawBytes != null ? new String(rawBytes, StandardCharsets.UTF_8) : "";
                if (!rawJson.isBlank() && rawJson.trim().startsWith("{")) {
                    root = objectMapper.readTree(rawJson);
                    break;
                } else {
                    log.warn("Overpass mirror {} returned non-JSON body (len={})", endpoint, rawJson.length());
                }
            } catch (Exception ex) {
                lastError = ex;
                log.warn("Overpass mirror {} failed for tile {}: {}", endpoint, tileKey, ex.getMessage());
            }
        }

        if (root == null) {
            throw new IllegalStateException("All Overpass mirrors failed for tile " + tileKey, lastError);
        }

        if (!root.has("elements") || !root.get("elements").isArray()) {
            return List.of();
        }

        List<Poi> pois = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        for (JsonNode el : root.get("elements")) {
            JsonNode tagsNode = el.get("tags");
            if (tagsNode == null || !tagsNode.has("name")) {
                continue;
            }
            String name = tagsNode.get("name").asText().trim();
            if (name.isEmpty() || !seenNames.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }

            Double lat = extractCoord(el, "lat");
            Double lng = extractCoord(el, "lon");
            if (lat == null || lng == null) {
                continue;
            }

            String externalId = "osm:" + el.path("type").asText("node") + ":" + el.path("id").asText();

            Map<String, String> tags = new HashMap<>();
            tagsNode.fields().forEachRemaining(entry -> {
                String k = entry.getKey();
                if (isRelevantTag(k)) {
                    tags.put(k, entry.getValue().asText());
                }
            });

            PoiCategory category = classifyCategory(tags);
            short priceTier = estimatePriceTier(category, tags);
            int estimatedCost = estimateBaseCostInr(category, priceTier);
            int avgDuration = estimateDurationMins(category);
            double rating = estimateRating(tags);

            Map<String, List<List<String>>> parsedHours = parseOpeningHours(tags.get("opening_hours"));
            boolean hoursEstimated = parsedHours.isEmpty();

            Poi poi = new Poi();
            poi.setExternalId(externalId);
            poi.setTileKey(tileKey);
            poi.setName(name);
            poi.setCategory(category);
            poi.setPriceTier(priceTier);
            poi.setEstimatedCost(estimatedCost);
            poi.setRating(rating);
            poi.setAvgDurationMins(avgDuration);
            poi.setTags(tags);
            poi.setOpeningHours(parsedHours);
            poi.setHoursEstimated(hoursEstimated);
            Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
            point.setSRID(4326);
            poi.setLocation(point);
            poi.setUpdatedAt(Instant.now());

            pois.add(poi);
        }

        log.info("Parsed {} unique POIs for tile {}", pois.size(), tileKey);
        return pois;
    }

    private Double extractCoord(JsonNode el, String field) {
        if (el.has(field) && el.get(field).isNumber()) {
            return el.get(field).asDouble();
        }
        JsonNode center = el.get("center");
        if (center != null && center.has(field) && center.get(field).isNumber()) {
            return center.get(field).asDouble();
        }
        return null;
    }

    @SuppressWarnings("unused")
    private List<Poi> fallbackEmpty(String tileKey, String destination, GeoPoint center, double south, double west, double north,
            double east, Throwable t) {
        log.warn("Overpass API failed for tile {}: {}", tileKey, t.getMessage());
        return List.of();
    }

    private boolean isRelevantTag(String key) {
        return Set.of("tourism", "historic", "leisure", "amenity", "shop", "cuisine",
                "opening_hours", "fee", "website", "wikipedia", "wikidata", "sport", "natural").contains(key);
    }

    private PoiCategory classifyCategory(Map<String, String> tags) {
        String amenity = tags.getOrDefault("amenity", "");
        if (amenity.equals("restaurant") || amenity.equals("fast_food"))
            return PoiCategory.RESTAURANT;
        if (amenity.equals("cafe"))
            return PoiCategory.CAFE;
        if (amenity.equals("bar") || amenity.equals("pub"))
            return PoiCategory.NIGHTLIFE;

        String natural = tags.getOrDefault("natural", "");
        if (natural.equals("peak") || natural.equals("waterfall") || natural.equals("cave_entrance")) {
            return PoiCategory.ADVENTURE;
        }
        if (natural.equals("beach") || natural.equals("hot_spring")) {
            return PoiCategory.NATURE;
        }

        String leisure = tags.getOrDefault("leisure", "");
        if (leisure.equals("water_park") || leisure.equals("sports_centre") || tags.containsKey("sport")) {
            return PoiCategory.ADVENTURE;
        }
        if (leisure.equals("park") || leisure.equals("garden") || leisure.equals("nature_reserve")) {
            return PoiCategory.NATURE;
        }

        String tourism = tags.getOrDefault("tourism", "");
        if (tourism.equals("theme_park"))
            return PoiCategory.ADVENTURE;
        if (tourism.equals("museum") || tourism.equals("gallery") || tags.containsKey("historic")) {
            return PoiCategory.CULTURE;
        }
        if (tags.containsKey("shop"))
            return PoiCategory.SHOPPING;

        return PoiCategory.ATTRACTION;
    }

    private short estimatePriceTier(PoiCategory category, Map<String, String> tags) {
        if ("no".equalsIgnoreCase(tags.get("fee")))
            return 1;
        if (category == PoiCategory.NATURE)
            return 1;
        if (category == PoiCategory.CAFE)
            return 2;
        if (category == PoiCategory.RESTAURANT) {
            String amenity = tags.getOrDefault("amenity", "");
            return amenity.equals("fast_food") ? (short) 1 : (short) 2;
        }
        if (category == PoiCategory.NIGHTLIFE || category == PoiCategory.ADVENTURE)
            return 3;
        return 2;
    }

    private int estimateBaseCostInr(PoiCategory category, short priceTier) {
        return switch (category) {
            case NATURE -> 50;
            case CAFE -> 250 * priceTier;
            case RESTAURANT -> 350 * priceTier;
            case NIGHTLIFE -> 600 * priceTier;
            case ADVENTURE -> 400 * priceTier;
            case SHOPPING -> 400 * priceTier;
            case CULTURE -> priceTier == 1 ? 50 : 200 * priceTier;
            case ATTRACTION -> priceTier == 1 ? 0 : 200 * priceTier;
        };
    }

    private int estimateDurationMins(PoiCategory category) {
        return switch (category) {
            case CAFE -> 45;
            case RESTAURANT -> 60;
            case NIGHTLIFE -> 90;
            case ADVENTURE -> 120;
            case CULTURE, ATTRACTION -> 90;
            case NATURE -> 75;
            case SHOPPING -> 60;
        };
    }

    private double estimateRating(Map<String, String> tags) {
        double rating = 4.0;
        if (tags.containsKey("wikipedia") || tags.containsKey("wikidata"))
            rating += 0.6;
        if (tags.containsKey("website"))
            rating += 0.2;
        if (tags.containsKey("opening_hours"))
            rating += 0.1;
        return Math.min(4.9, Math.round(rating * 10.0) / 10.0);
    }

    private Map<String, List<List<String>>> parseOpeningHours(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        if (raw.contains("24/7")) {
            return uniformWeek("00:00", "23:59");
        }
        Matcher m = SIMPLE_HOURS.matcher(raw);
        if (m.find()) {
            String open = padTime(m.group(1));
            String close = padTime(m.group(2));
            if (close.equals("24:00")) close = "23:59";
            return uniformWeek(open, close);
        }
        return Map.of();
    }

    private String padTime(String t) {
        return t.length() == 4 ? "0" + t : t;
    }

    private Map<String, List<List<String>>> uniformWeek(String open, String close) {
        Map<String, List<List<String>>> map = new HashMap<>();
        List<List<String>> slots = List.of(List.of(open, close));
        for (String day : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            map.put(day, slots);
        }
        return map;
    }
}
