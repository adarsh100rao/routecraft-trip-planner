package com.tripengine.domain.model;

import java.time.LocalTime;

public record TimeWindow(LocalTime open, LocalTime close) {
    public boolean contains(LocalTime start, LocalTime end) {
        if (close.isBefore(open)) {
            // Handles venues open past midnight (e.g. 18:00 - 02:00)
            return !start.isBefore(open);
        }
        return !start.isBefore(open) && !end.isAfter(close);
    }
}
