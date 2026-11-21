package com.tripengine.service.optimizer;

import com.tripengine.domain.model.DayConstraints;
import com.tripengine.domain.model.PlannedStop;
import com.tripengine.domain.model.ScoredPoi;
import com.tripengine.domain.model.TravelMatrix;

import java.util.List;

public interface ItineraryOptimizer {
    List<PlannedStop> optimizeDay(
            List<ScoredPoi> candidates,
            TravelMatrix matrix,
            DayConstraints constraints,
            List<PlannedStop> lockedStops
    );
}
