package com.tripengine.integration.geocoding;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripengine.config.TripEngineProperties;
import com.tripengine.domain.model.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NominatimGeocodingClient implements GeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingClient.class);

    private final RestClient restClient;
    private final TripEngineProperties properties;
    private final Map<String, GeocodedLocation> localCache = new ConcurrentHashMap<>();

    public NominatimGeocodingClient(
            @Qualifier("externalRestClient") RestClient restClient,
            TripEngineProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public GeocodedLocation geocode(String destination) {
        String key = destination.trim().toLowerCase(Locale.ROOT);
        GeocodedLocation cached = localCache.get(key);
        if (cached != null) {
            return cached;
        }

        URI uri = UriComponentsBuilder
                .fromUriString(properties.external().nominatimBaseUrl() + "/search")
                .queryParam("q", destination)
                .queryParam("format", "json")
                .queryParam("limit", 1)
                .build()
                .encode()
                .toUri();

        log.info("Geocoding destination '{}' via Nominatim", destination);
        JsonNode response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.isArray() || response.isEmpty()) {
            throw new IllegalArgumentException("Could not find destination: " + destination);
        }

        JsonNode first = response.get(0);
        double lat = first.get("lat").asDouble();
        double lng = first.get("lon").asDouble();
        String displayName = first.has("display_name") ? first.get("display_name").asText() : destination;

        GeocodedLocation result = new GeocodedLocation(displayName, new GeoPoint(lat, lng));
        localCache.put(key, result);
        return result;
    }
}
