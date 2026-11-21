package com.tripengine.domain.model;

import java.util.List;

public record DayCluster(
        int dayNumber,
        GeoPoint centroid,
        List<ScoredPoi> candidates
) {}
