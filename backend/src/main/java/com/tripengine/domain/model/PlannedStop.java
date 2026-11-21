package com.tripengine.domain.model;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.SlotType;

import java.time.LocalTime;

public record PlannedStop(
        Poi poi,
        LocalTime startTime,
        LocalTime endTime,
        int travelMinsFromPrev,
        int estimatedGroupCost,
        boolean locked,
        SlotType slotType
) {}
