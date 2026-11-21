package com.tripengine.integration.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripengine.config.TripEngineProperties;
import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.model.GeoPoint;
import com.tripengine.domain.model.TravelMatrix;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class OsrmRoutingClient implements RoutingClient {

    private static final Logger log = LoggerFactory.getLogger(OsrmRoutingClient.class);

    private final RestClient osrmRestClient;
    private final TripEngineProperties properties;
    private final HaversineFallbackClient fallbackClient;
    private final Timer osrmTimer;

    public OsrmRoutingClient(
            @Qualifier("osrmRestClient") RestClient osrmRestClient,
            TripEngineProperties properties,
            HaversineFallbackClient fallbackClient,
            MeterRegistry meterRegistry) {
        this.osrmRestClient = osrmRestClient;
        this.properties = properties;
        this.fallbackClient = fallbackClient;
        this.osrmTimer = Timer.builder("trip.osrm.duration")
                .description("OSRM external routing latency")
                .register(meterRegistry);
    }

    @Override
    @CircuitBreaker(name = "osrm", fallbackMethod = "fallbackMatrix")
    public TravelMatrix getTravelTimeMatrix(List<Poi> pois) {
        if (pois.isEmpty()) {
            return new TravelMatrix(Map.of(), new int[0][0], "OSRM");
        }
        if (pois.size() == 1) {
            return new TravelMatrix(Map.of(pois.get(0).getId(), 0), new int[][]{{0}}, "OSRM");
        }

        return osrmTimer.record(() -> {
            String coords = pois.stream()
                    .map(p -> String.format(Locale.US, "%.5f,%.5f", p.getLng(), p.getLat()))
                    .collect(Collectors.joining(";"));

            String url = properties.external().osrmBaseUrl()
                    + "/table/v1/driving/" + coords + "?annotations=duration";

            JsonNode root = osrmRestClient.get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || !"Ok".equalsIgnoreCase(root.path("code").asText()) || !root.has("durations")) {
                throw new IllegalStateException("Invalid OSRM table response");
            }

            JsonNode durations = root.get("durations");
            int n = pois.size();
            Map<UUID, Integer> indexMap = new HashMap<>(n);
            int[][] matrix = new int[n][n];

            for (int i = 0; i < n; i++) {
                indexMap.put(pois.get(i).getId(), i);
                JsonNode row = durations.get(i);
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        matrix[i][j] = 0;
                    } else if (row != null && row.has(j) && !row.get(j).isNull()) {
                        int mins = (int) Math.ceil(row.get(j).asDouble() / 60.0);
                        matrix[i][j] = Math.max(3, mins);
                    } else {
                        matrix[i][j] = 20;
                    }
                }
            }
            return new TravelMatrix(indexMap, matrix, "OSRM");
        });
    }

    @Override
    @CircuitBreaker(name = "osrm", fallbackMethod = "fallbackPolyline")
    public String getRoutePolyline(List<GeoPoint> orderedPoints) {
        if (orderedPoints == null || orderedPoints.size() < 2) {
            return "";
        }
        String coords = orderedPoints.stream()
                .map(p -> String.format(Locale.US, "%.5f,%.5f", p.lng(), p.lat()))
                .collect(Collectors.joining(";"));

        String url = properties.external().osrmBaseUrl()
                + "/route/v1/driving/" + coords + "?overview=full&geometries=polyline";

        JsonNode root = osrmRestClient.get()
                .uri(url)
                .retrieve()
                .body(JsonNode.class);

        if (root != null && "Ok".equalsIgnoreCase(root.path("code").asText())
                && root.has("routes") && !root.get("routes").isEmpty()) {
            return root.get("routes").get(0).path("geometry").asText("");
        }
        return fallbackClient.encodeStraightLinePolyline(orderedPoints);
    }

    @SuppressWarnings("unused")
    private TravelMatrix fallbackMatrix(List<Poi> pois, Throwable t) {
        log.warn("OSRM /table unavailable ({}), falling back to Haversine estimation", t.getMessage());
        return fallbackClient.estimateMatrix(pois);
    }

    @SuppressWarnings("unused")
    private String fallbackPolyline(List<GeoPoint> orderedPoints, Throwable t) {
        log.warn("OSRM /route unavailable ({}), encoding straight-line polyline", t.getMessage());
        return fallbackClient.encodeStraightLinePolyline(orderedPoints);
    }
}
