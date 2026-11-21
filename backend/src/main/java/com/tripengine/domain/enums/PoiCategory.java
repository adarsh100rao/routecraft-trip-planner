package com.tripengine.domain.enums;

public enum PoiCategory {
    ATTRACTION,
    RESTAURANT,
    CAFE,
    NATURE,
    ADVENTURE,
    CULTURE,
    NIGHTLIFE,
    SHOPPING;

    public boolean isFood() {
        return this == RESTAURANT || this == CAFE;
    }
}
