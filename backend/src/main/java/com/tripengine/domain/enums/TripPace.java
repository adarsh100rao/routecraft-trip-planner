package com.tripengine.domain.enums;

import java.time.LocalTime;

public enum TripPace {
    RELAXED(LocalTime.of(10, 0), LocalTime.of(20, 0), 4),
    MODERATE(LocalTime.of(9, 0), LocalTime.of(21, 0), 6),
    PACKED(LocalTime.of(8, 30), LocalTime.of(22, 0), 8);

    private final LocalTime dayStart;
    private final LocalTime dayEnd;
    private final int maxStopsPerDay;

    TripPace(LocalTime dayStart, LocalTime dayEnd, int maxStopsPerDay) {
        this.dayStart = dayStart;
        this.dayEnd = dayEnd;
        this.maxStopsPerDay = maxStopsPerDay;
    }

    public LocalTime getDayStart() {
        return dayStart;
    }

    public LocalTime getDayEnd() {
        return dayEnd;
    }

    public int getMaxStopsPerDay() {
        return maxStopsPerDay;
    }
}
