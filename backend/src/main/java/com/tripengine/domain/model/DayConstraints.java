package com.tripengine.domain.model;

import com.tripengine.domain.enums.TripPace;

import java.time.DayOfWeek;

public record DayConstraints(
        int dayNumber,
        DayOfWeek dayOfWeek,
        TripPace pace,
        int peopleCount,
        int dailyBudget
) {}
