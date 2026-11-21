package com.tripengine.service.clustering;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.PoiCategory;
import com.tripengine.domain.model.DayCluster;
import com.tripengine.domain.model.ScoredPoi;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BalancedKMeansClustererTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);
    private final BalancedKMeansClusterer clusterer = new BalancedKMeansClusterer();

    @Test
    void clustersGeographicallySeparatedRegionsIntoBalancedDays() {
        List<ScoredPoi> candidates = new ArrayList<>();
        // North zone (10 POIs)
        for (int i = 0; i < 10; i++) {
            candidates.add(makePoi("North-" + i, 26.98 + i * 0.002, 75.85 + i * 0.002, 0.9 - i * 0.01));
        }
        // South zone (10 POIs)
        for (int i = 0; i < 10; i++) {
            candidates.add(makePoi("South-" + i, 26.85 + i * 0.002, 75.78 + i * 0.002, 0.85 - i * 0.01));
        }
        // East zone (10 POIs)
        for (int i = 0; i < 10; i++) {
            candidates.add(makePoi("East-" + i, 26.91 + i * 0.002, 75.95 + i * 0.002, 0.80 - i * 0.01));
        }

        List<DayCluster> clusters = clusterer.cluster(candidates, 3);

        assertThat(clusters).hasSize(3);
        int totalAssigned = clusters.stream().mapToInt(c -> c.candidates().size()).sum();
        assertThat(totalAssigned).isEqualTo(30);
        // Each cluster should be balanced (capacity <= ceil(30/3) + 2 = 12)
        for (DayCluster cluster : clusters) {
            assertThat(cluster.candidates().size()).isBetween(8, 12);
        }
    }

    private ScoredPoi makePoi(String name, double lat, double lng, double score) {
        Poi poi = new Poi();
        poi.setId(UUID.randomUUID());
        poi.setName(name);
        poi.setCategory(PoiCategory.ATTRACTION);
        poi.setLocation(GF.createPoint(new Coordinate(lng, lat)));
        return new ScoredPoi(poi, score);
    }
}
