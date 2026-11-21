package com.tripengine.integration.geocoding;

import com.tripengine.domain.model.GeoPoint;

public interface GeocodingClient {
    GeocodedLocation geocode(String destination);

    record GeocodedLocation(String displayName, GeoPoint center) {}
}
