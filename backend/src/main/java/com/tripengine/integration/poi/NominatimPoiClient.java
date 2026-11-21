package com.tripengine.integration.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripengine.config.TripEngineProperties;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Queries OpenStreetMap Nominatim within a strict bounding viewbox (bounded=1, extratags=1)
 * to retrieve verified restaurants and attractions along with real OSM opening_hours & cuisine tags.
 */
@Component
public class NominatimPoiClient {

    private static final Logger log = LoggerFactory.getLogger(NominatimPoiClient.class);
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);
    private static final Pattern SIMPLE_HOURS = Pattern.compile("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})");
    private static final Set<String> GENERIC_NAMES = Set.of(
            "restaurant", "cafe", "café", "park", "garden", "hotel", "shop", "mall", "food court"
    );

    private final RestClient restClient;
    private final TripEngineProperties properties;
    private final ObjectMapper objectMapper;

    public NominatimPoiClient(
            @Qualifier("externalRestClient") RestClient restClient,
            TripEngineProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<Poi> fetchBoundedPois(String tileKey, double south, double west, double north, double east) {
        String viewbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", west, north, east, south);
        List<Poi> results = new ArrayList<>();
        // Query real OSM classes that Nominatim indexes reliably in viewbox mode
        results.addAll(queryViewbox(tileKey, viewbox, "restaurant", PoiCategory.RESTAURANT));
        results.addAll(queryViewbox(tileKey, viewbox, "cafe", PoiCategory.CAFE));
        results.addAll(queryViewbox(tileKey, viewbox, "park", PoiCategory.NATURE));
        log.info("Nominatim viewbox returned {} POIs for tile {}", results.size(), tileKey);
        return results;
    }

    private List<Poi> queryViewbox(String tileKey, String viewbox, String query, PoiCategory fallbackCategory) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(properties.external().nominatimBaseUrl() + "/search")
                    .queryParam("q", query)
                    .queryParam("viewbox", viewbox)
                    .queryParam("bounded", 1)
                    .queryParam("format", "json")
                    .queryParam("limit", 30)
                    .queryParam("extratags", 1)
                    .build()
                    .encode()
                    .toUri();

            byte[] raw = restClient.get().uri(uri).retrieve().body(byte[].class);
            if (raw == null || raw.length == 0) {
                return List.of();
            }

            JsonNode arr = objectMapper.readTree(raw);
            if (!arr.isArray()) {
                return List.of();
            }

            List<Poi> pois = new ArrayList<>();
            for (JsonNode item : arr) {
                String name = item.path("name").asText("").trim();
                if (name.length() < 3 || GENERIC_NAMES.contains(name.toLowerCase(Locale.ROOT))) continue;

                String osmClass = item.path("class").asText("");
                String osmTypeVal = item.path("type").asText("");
                if (Set.of("hotel", "hostel", "guest_house", "motel").contains(osmTypeVal)) continue;

                double lat = item.path("lat").asDouble();
                double lng = item.path("lon").asDouble();
                String osmId = item.path("osm_id").asText(item.path("place_id").asText());
                String osmTypePrefix = item.path("osm_type").asText("node").substring(0, 1).toUpperCase(Locale.ROOT);

                Map<String, String> tags = new HashMap<>();
                tags.put("source", "nominatim-osm");
                if (!osmClass.isEmpty()) tags.put(osmClass, osmTypeVal);
                JsonNode extra = item.path("extratags");
                if (extra.isObject()) {
                    extra.fields().forEachRemaining(e -> {
                        if (Set.of("cuisine", "opening_hours", "website", "wikipedia", "fee").contains(e.getKey())) {
                            tags.put(e.getKey(), e.getValue().asText());
                        }
                    });
                }

                PoiCategory category = classify(osmClass, osmTypeVal, fallbackCategory);
                short priceTier = category == PoiCategory.NATURE ? (short) 1 : (short) 2;
                Map<String, List<List<String>>> parsedHours = parseOpeningHours(tags.get("opening_hours"));

                Poi poi = new Poi();
                poi.setExternalId("osm:" + osmTypePrefix + ":" + osmId);
                poi.setTileKey(tileKey);
                poi.setName(name);
                poi.setCategory(category);
                poi.setPriceTier(priceTier);
                poi.setEstimatedCost(category.isFood() ? 350 * priceTier : 200 * priceTier);
                poi.setRating(tags.containsKey("wikipedia") ? 4.6 : 4.3);
                poi.setAvgDurationMins(category.isFood() ? 60 : 90);
                poi.setTags(tags);
                poi.setOpeningHours(parsedHours);
                poi.setHoursEstimated(parsedHours.isEmpty());

                Point pt = GF.createPoint(new Coordinate(lng, lat));
                pt.setSRID(4326);
                poi.setLocation(pt);
                poi.setUpdatedAt(Instant.now());
                pois.add(poi);
            }
            return pois;
        } catch (Exception ex) {
            log.warn("Nominatim viewbox query '{}' failed: {}", query, ex.getMessage());
            return List.of();
        }
    }

    private PoiCategory classify(String osmClass, String osmType, PoiCategory fallback) {
        if (osmClass.equals("amenity")) {
            if (osmType.equals("cafe")) return PoiCategory.CAFE;
            if (osmType.equals("bar") || osmType.equals("pub")) return PoiCategory.NIGHTLIFE;
            if (osmType.equals("restaurant") || osmType.equals("fast_food")) return PoiCategory.RESTAURANT;
        }
        if (osmClass.equals("historic") || osmType.equals("museum")) return PoiCategory.CULTURE;
        if (osmClass.equals("leisure") || osmClass.equals("natural")) return PoiCategory.NATURE;
        return fallback;
    }

    private Map<String, List<List<String>>> parseOpeningHours(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        if (raw.contains("24/7")) return uniformWeek("00:00", "23:59");
        Matcher m = SIMPLE_HOURS.matcher(raw);
        if (m.find()) {
            String open = m.group(1).length() == 4 ? "0" + m.group(1) : m.group(1);
            String close = m.group(2).length() == 4 ? "0" + m.group(2) : m.group(2);
            if (close.equals("24:00")) close = "23:59";
            return uniformWeek(open, close);
        }
        return Map.of();
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
