package com.tripengine.domain.model;

import com.tripengine.domain.entity.Poi;

public record ScoredPoi(Poi poi, double score) {
    public GeoPoint location() {
        return new GeoPoint(poi.getLat(), poi.getLng());
    }
}
