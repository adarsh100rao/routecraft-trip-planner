package com.tripengine.dto.response;

import com.tripengine.domain.enums.PoiCategory;
import com.tripengine.domain.enums.SlotType;

import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

public record StopResponse(
        UUID stopId,
        UUID poiId,
        String name,
        PoiCategory category,
        SlotType slotType,
        int orderIndex,
        LocalTime startTime,
        LocalTime endTime,
        int travelMinsFromPrev,
        int estimatedCost,
        short priceTier,
        double rating,
        double lat,
        double lng,
        boolean locked,
        boolean hoursEstimated,
        Map<String, String> tags
) {}
