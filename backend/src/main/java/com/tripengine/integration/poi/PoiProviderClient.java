package com.tripengine.integration.poi;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.model.GeoPoint;

import java.util.List;

public interface PoiProviderClient {
    List<Poi> fetchPoisInBoundingBox(
            String tileKey, String destination, GeoPoint center,
            double south, double west, double north, double east);
}
