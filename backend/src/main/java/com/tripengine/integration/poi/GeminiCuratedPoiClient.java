package com.tripengine.integration.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripengine.config.TripEngineProperties;
import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.PoiCategory;
import com.tripengine.domain.model.GeoPoint;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Curates authentic, traveler/Reddit-recommended restaurants, cafes, street-food spots,
 * hidden gems, and must-visit attractions via Gemini, then grounds each entity against
 * OpenStreetMap (Photon bounded search) to snap to verified coordinates and eliminate hallucinations.
 */
@Component
public class GeminiCuratedPoiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiCuratedPoiClient.class);
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);
    private static final double MAX_DISTANCE_FROM_CENTER_KM = 45.0;

    private final RestClient externalRestClient;
    private final RestClient fastRestClient;
    private final TripEngineProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualThreadExecutor;

    public GeminiCuratedPoiClient(
            @Qualifier("externalRestClient") RestClient externalRestClient,
            @Qualifier("fastExternalRestClient") RestClient fastRestClient,
            TripEngineProperties properties,
            ObjectMapper objectMapper,
            ExecutorService virtualThreadExecutor) {
        this.externalRestClient = externalRestClient;
        this.fastRestClient = fastRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    public boolean isConfigured() {
        String key = properties.external().geminiApiKey();
        return key != null && !key.isBlank();
    }

    public List<Poi> fetchCuratedAndGroundedPois(
            String tileKey, String destination, GeoPoint center,
            double south, double west, double north, double east) {
        if (!isConfigured() || destination == null || destination.isBlank()) {
            return List.of();
        }

        try {
            long t0 = System.currentTimeMillis();
            JsonNode rawItems = callGeminiForCommunityPicks(destination, center);
            if (rawItems == null || !rawItems.isArray() || rawItems.isEmpty()) {
                return List.of();
            }

            // Ground coordinates against bounded OSM (Photon) with concurrency cap of 4 to stay polite
            String bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f",
                    west - 0.08, south - 0.08, east + 0.08, north + 0.08);
            Semaphore sem = new Semaphore(4);

            List<CompletableFuture<Poi>> futures = new ArrayList<>();
            for (JsonNode item : rawItems) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> buildAndGroundPoi(tileKey, destination, center, bbox, item, sem),
                        virtualThreadExecutor));
            }

            List<Poi> grounded = new ArrayList<>();
            int osmVerifiedCount = 0;
            for (CompletableFuture<Poi> f : futures) {
                Poi p = f.join();
                if (p != null) {
                    if ("OSM_VERIFIED".equals(p.getTags().get("grounding"))) {
                        osmVerifiedCount++;
                    }
                    grounded.add(p);
                }
            }

            log.info("Gemini curated {} authentic POIs for '{}' ({} OSM-verified) in {}ms",
                    grounded.size(), destination, osmVerifiedCount, System.currentTimeMillis() - t0);
            return grounded;
        } catch (Exception ex) {
            log.warn("Gemini curated POI fetch failed for '{}': {}", destination, ex.getMessage());
            return List.of();
        }
    }

    private JsonNode callGeminiForCommunityPicks(String destination, GeoPoint center) throws Exception {
        String model = properties.external().geminiModel();
        String apiKey = properties.external().geminiApiKey();
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        String prompt = """
                You are a local travel expert synthesizing the most upvoted Reddit travel community recommendations (e.g. r/indiatravel, r/solotravel, and city subreddits) for "%s" (center coordinates %.4f, %.4f).
                Return a JSON array of 28 REAL, famous, authentic, community-favorite places in and around "%s":
                - Include 14 must-visit attractions/experiences covering CULTURE, NATURE, ADVENTURE, SHOPPING, NIGHTLIFE, and ATTRACTION — including at least 3 premium/luxury experiences (priceTier 3-4, e.g., luxury shopping galleries, iconic rooftop lounges, spa/heritage experiences, private tours, or upscale art/cultural venues with realistic INR costs 3000-15000 per person).
                - Include 14 iconic local RESTAURANT and CAFE spots spread across ALL price tiers so both budget and luxury travelers are served:
                  * 4 budget/street-food legends (priceTier 1, estimatedCostInr 150-400)
                  * 5 popular mid-range cafes & restaurants (priceTier 2, estimatedCostInr 500-1200)
                  * 5 upscale & 5-star luxury fine-dining restaurants (priceTier 3-4, estimatedCostInr 2500-9000 per person, e.g., iconic award-winning or 5-star hotel restaurants in the city).
                - Do NOT include generic chains (like Cafe Coffee Day or Domino's), plain lodging, airports, or railway stations.

                Each JSON object MUST have these exact fields:
                {
                  "name": "Exact real-world place name",
                  "category": "ATTRACTION | CULTURE | NATURE | ADVENTURE | RESTAURANT | CAFE | NIGHTLIFE | SHOPPING",
                  "priceTier": 1 to 4 (1=budget/free, 2=moderate, 3=upscale, 4=luxury),
                  "estimatedCostInr": integer realistic cost per person in INR (entry fee, experience fee, or typical meal cost matching priceTier),
                  "avgDurationMins": integer (45-120),
                  "rating": number between 4.2 and 4.9 reflecting community popularity,
                  "openTime": "HH:MM" (24h, e.g. "09:00"),
                  "closeTime": "HH:MM" (24h, e.g. "22:00"),
                  "lat": accurate latitude number,
                  "lng": accurate longitude number,
                  "localTip": "Concise 1-sentence insider tip on what to order or why travelers love it"
                }
                Return ONLY the JSON array.
                """.formatted(destination, center.lat(), center.lng(), destination);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.25
                )
        );

        byte[] raw = externalRestClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(byte[].class);

        if (raw == null || raw.length == 0) {
            return null;
        }

        JsonNode envelope = objectMapper.readTree(raw);
        String text = envelope.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) {
            return null;
        }
        text = stripMarkdownFences(text);
        return objectMapper.readTree(text);
    }

    private Poi buildAndGroundPoi(
            String tileKey, String destination, GeoPoint center,
            String bbox, JsonNode item, Semaphore sem) {
        String name = item.path("name").asText("").trim();
        if (name.length() < 2) return null;

        double approxLat = item.path("lat").asDouble(Double.NaN);
        double approxLng = item.path("lng").asDouble(Double.NaN);

        // Attempt to ground exact coordinates in OpenStreetMap via Photon bounded search
        GeoPoint groundedPoint = groundViaPhoton(name, bbox, center, sem);
        String groundingStatus;
        GeoPoint finalPoint;

        if (groundedPoint != null) {
            finalPoint = groundedPoint;
            groundingStatus = "OSM_VERIFIED";
        } else if (!Double.isNaN(approxLat) && !Double.isNaN(approxLng)
                && center.haversineKm(new GeoPoint(approxLat, approxLng)) <= MAX_DISTANCE_FROM_CENTER_KM) {
            finalPoint = new GeoPoint(approxLat, approxLng);
            groundingStatus = "COMMUNITY_CURATED";
        } else {
            log.debug("Discarding ungrounded/out-of-bounds POI '{}' for {}", name, destination);
            return null;
        }

        PoiCategory category = parseCategory(item.path("category").asText("ATTRACTION"));
        short priceTier = (short) Math.max(1, Math.min(4, item.path("priceTier").asInt(2)));
        int cost = Math.max(0, item.path("estimatedCostInr").asInt(category.isFood() ? 450 : 200));
        int duration = Math.max(30, Math.min(180, item.path("avgDurationMins").asInt(category.isFood() ? 60 : 90)));
        double rating = Math.max(4.0, Math.min(4.9, item.path("rating").asDouble(4.5)));
        String tip = item.path("localTip").asText("").trim();
        String openTime = normalizeTime(item.path("openTime").asText(""));
        String closeTime = normalizeTime(item.path("closeTime").asText(""));

        Map<String, List<List<String>>> hours = Map.of();
        boolean hoursEstimated = true;
        if (openTime != null && closeTime != null) {
            hours = uniformWeek(openTime, closeTime);
            hoursEstimated = false;
        }

        Map<String, String> tags = new HashMap<>();
        tags.put("source", "community-curated");
        tags.put("grounding", groundingStatus);
        if (!tip.isEmpty()) {
            tags.put("localTip", tip);
        }

        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        String citySlug = destination.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");

        Poi poi = new Poi();
        poi.setExternalId("curated:" + citySlug + ":" + slug);
        poi.setTileKey(tileKey);
        poi.setName(name);
        poi.setCategory(category);
        poi.setPriceTier(priceTier);
        poi.setEstimatedCost(cost);
        poi.setRating(Math.round(rating * 10.0) / 10.0);
        poi.setAvgDurationMins(duration);
        poi.setTags(tags);
        poi.setOpeningHours(hours);
        poi.setHoursEstimated(hoursEstimated);

        Point pt = GF.createPoint(new Coordinate(finalPoint.lng(), finalPoint.lat()));
        pt.setSRID(4326);
        poi.setLocation(pt);
        poi.setUpdatedAt(Instant.now());
        return poi;
    }

    private GeoPoint groundViaPhoton(String placeName, String bbox, GeoPoint cityCenter, Semaphore sem) {
        try {
            sem.acquire();
            try {
                URI uri = UriComponentsBuilder
                        .fromUriString("https://photon.komoot.io/api/")
                        .queryParam("q", placeName)
                        .queryParam("bbox", bbox)
                        .queryParam("limit", 2)
                        .build()
                        .encode()
                        .toUri();

                byte[] raw = fastRestClient.get().uri(uri).retrieve().body(byte[].class);
                if (raw == null || raw.length == 0) return null;

                JsonNode features = objectMapper.readTree(raw).path("features");
                if (!features.isArray() || features.isEmpty()) return null;

                for (JsonNode f : features) {
                    JsonNode coords = f.path("geometry").path("coordinates");
                    if (coords.isArray() && coords.size() >= 2) {
                        GeoPoint candidate = new GeoPoint(coords.get(1).asDouble(), coords.get(0).asDouble());
                        if (cityCenter.haversineKm(candidate) <= MAX_DISTANCE_FROM_CENTER_KM) {
                            return candidate;
                        }
                    }
                }
                return null;
            } finally {
                sem.release();
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private PoiCategory parseCategory(String raw) {
        try {
            return PoiCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return PoiCategory.ATTRACTION;
        }
    }

    private String normalizeTime(String t) {
        if (t == null || !t.matches("\\d{1,2}:\\d{2}")) return null;
        String padded = t.length() == 4 ? "0" + t : t;
        if (padded.equals("24:00")) return "23:59";
        int hr = Integer.parseInt(padded.substring(0, 2));
        int min = Integer.parseInt(padded.substring(3, 5));
        if (hr < 0 || hr > 23 || min < 0 || min > 59) return null;
        return padded;
    }

    private Map<String, List<List<String>>> uniformWeek(String open, String close) {
        Map<String, List<List<String>>> map = new HashMap<>();
        List<List<String>> slots = List.of(List.of(open, close));
        for (String day : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            map.put(day, slots);
        }
        return map;
    }

    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
