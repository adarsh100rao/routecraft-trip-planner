package com.tripengine.integration.routing;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.model.GeoPoint;
import com.tripengine.domain.model.TravelMatrix;

import java.util.List;

public interface RoutingClient {
    TravelMatrix getTravelTimeMatrix(List<Poi> pois);
    String getRoutePolyline(List<GeoPoint> orderedPoints);
}
