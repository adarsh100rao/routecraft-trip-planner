package com.tripengine.integration.routing;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.model.GeoPoint;
import com.tripengine.domain.model.TravelMatrix;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class HaversineFallbackClient {

    private static final double URBAN_AVG_SPEED_KMH = 22.0;

    public TravelMatrix estimateMatrix(List<Poi> pois) {
        int n = pois.size();
        Map<UUID, Integer> indexMap = new HashMap<>(n);
        int[][] matrix = new int[n][n];

        for (int i = 0; i < n; i++) {
            indexMap.put(pois.get(i).getId(), i);
        }

        for (int i = 0; i < n; i++) {
            GeoPoint from = new GeoPoint(pois.get(i).getLat(), pois.get(i).getLng());
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    matrix[i][j] = 0;
                } else {
                    GeoPoint to = new GeoPoint(pois.get(j).getLat(), pois.get(j).getLng());
                    double distKm = from.haversineKm(to) * 1.3; // road winding factor
                    matrix[i][j] = Math.max(5, (int) Math.round((distKm / URBAN_AVG_SPEED_KMH) * 60.0));
                }
            }
        }
        return new TravelMatrix(indexMap, matrix, "ESTIMATED");
    }

    /**
     * Encodes straight-line waypoints in Google Encoded Polyline format so Leaflet can still draw paths.
     */
    public String encodeStraightLinePolyline(List<GeoPoint> points) {
        if (points == null || points.size() < 2) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        long lastLat = 0;
        long lastLng = 0;
        for (GeoPoint p : points) {
            long lat = Math.round(p.lat() * 1e5);
            long lng = Math.round(p.lng() * 1e5);
            encodeValue(lat - lastLat, result);
            encodeValue(lng - lastLng, result);
            lastLat = lat;
            lastLng = lng;
        }
        return result.toString();
    }

    private void encodeValue(long v, StringBuilder out) {
        v = v < 0 ? ~(v << 1) : (v << 1);
        while (v >= 0x20) {
            out.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        out.append((char) (v + 63));
    }
}
