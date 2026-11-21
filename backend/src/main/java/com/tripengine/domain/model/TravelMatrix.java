package com.tripengine.domain.model;

import java.util.Map;
import java.util.UUID;

public record TravelMatrix(
        Map<UUID, Integer> indexByPoiId,
        int[][] durationMinutes,
        String routingMode // "OSRM" or "ESTIMATED"
) {
    public int travelMinutes(UUID fromPoiId, UUID toPoiId) {
        if (fromPoiId == null || toPoiId == null || fromPoiId.equals(toPoiId)) {
            return 0;
        }
        Integer i = indexByPoiId.get(fromPoiId);
        Integer j = indexByPoiId.get(toPoiId);
        if (i == null || j == null) {
            return 15;
        }
        return Math.max(0, durationMinutes[i][j]);
    }
}
