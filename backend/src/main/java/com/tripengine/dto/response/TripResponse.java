package com.tripengine.dto.response;

import com.tripengine.domain.enums.TripPace;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TripResponse(
        UUID tripId,
        String destination,
        double centerLat,
        double centerLng,
        LocalDate startDate,
        int days,
        int peopleCount,
        int totalBudget,
        String currency,
        TripPace pace,
        List<String> preferences,
        int estimatedTotalCost,
        int foodCost,
        int activityCost,
        String routingMode,
        boolean cacheHit,
        long generationTimeMs,
        Instant createdAt,
        List<DayPlanResponse> dayPlans
) {}
