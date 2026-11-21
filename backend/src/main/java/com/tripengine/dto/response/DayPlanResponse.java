package com.tripengine.dto.response;

import java.time.LocalDate;
import java.util.List;

public record DayPlanResponse(
        int dayNumber,
        LocalDate date,
        int totalEstimatedCost,
        int totalTravelMinutes,
        String routePolyline,
        List<StopResponse> stops
) {}
