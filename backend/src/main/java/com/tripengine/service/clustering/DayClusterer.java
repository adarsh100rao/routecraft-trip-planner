package com.tripengine.service.clustering;

import com.tripengine.domain.model.DayCluster;
import com.tripengine.domain.model.ScoredPoi;

import java.util.List;

public interface DayClusterer {
    List<DayCluster> cluster(List<ScoredPoi> candidates, int numDays);
}
