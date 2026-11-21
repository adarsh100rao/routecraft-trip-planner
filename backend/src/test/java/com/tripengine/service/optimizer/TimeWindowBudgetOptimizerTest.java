package com.tripengine.service.optimizer;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.PoiCategory;
import com.tripengine.domain.enums.SlotType;
import com.tripengine.domain.enums.TripPace;
import com.tripengine.domain.model.DayConstraints;
import com.tripengine.domain.model.PlannedStop;
import com.tripengine.domain.model.ScoredPoi;
import com.tripengine.domain.model.TravelMatrix;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TimeWindowBudgetOptimizerTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private TwoOptRefiner twoOptRefiner;
    private TimeWindowBudgetOptimizer optimizer;

    @BeforeEach
    void setUp() {
        twoOptRefiner = new TwoOptRefiner();
        optimizer = new TimeWindowBudgetOptimizer(twoOptRefiner, new SimpleMeterRegistry());
    }

    @Test
    void enforcesHardDailyBudgetAndSchedulesMeals() {
        List<ScoredPoi> candidates = List.of(
                scoredPoi("Amber Fort", PoiCategory.CULTURE, 500, 90, 0.95, Map.of()),
                scoredPoi("Hawa Mahal", PoiCategory.CULTURE, 400, 75, 0.90, Map.of()),
                scoredPoi("City Palace", PoiCategory.ATTRACTION, 800, 90, 0.88, Map.of()),
                scoredPoi("Luxury Spa", PoiCategory.ATTRACTION, 4500, 90, 0.99, Map.of()), // Too expensive for 2 people!
                scoredPoi("Spice Court Lunch", PoiCategory.RESTAURANT, 600, 60, 0.92, Map.of()),
                scoredPoi("Chokhi Dhani Dinner", PoiCategory.RESTAURANT, 700, 60, 0.91, Map.of()),
                scoredPoi("Nahargarh Sunset", PoiCategory.NATURE, 100, 75, 0.86, Map.of())
        );

        TravelMatrix matrix = uniformMatrix(candidates, 12);
        DayConstraints constraints = new DayConstraints(1, DayOfWeek.SATURDAY, TripPace.MODERATE, 2, 5000);

        List<PlannedStop> plan = optimizer.optimizeDay(candidates, matrix, constraints, List.of());

        assertThat(plan).isNotEmpty();
        int totalCost = plan.stream().mapToInt(PlannedStop::estimatedGroupCost).sum();
        assertThat(totalCost).isLessThanOrEqualTo(5000);
        assertThat(plan).noneMatch(s -> s.poi().getName().equals("Luxury Spa"));
        assertThat(plan).anyMatch(s -> s.slotType() == SlotType.LUNCH);
    }

    @Test
    void respectsOpeningHoursAndPreservesLockedStops() {
        Map<String, List<List<String>>> morningOnly = Map.of("sat", List.of(List.of("09:00", "11:00")));
        ScoredPoi morningFort = scoredPoi("Morning Fort", PoiCategory.CULTURE, 200, 90, 0.95, morningOnly);
        ScoredPoi lockedMuseum = scoredPoi("Locked Museum", PoiCategory.CULTURE, 300, 90, 0.90, Map.of());
        ScoredPoi afternoonPark = scoredPoi("Afternoon Park", PoiCategory.NATURE, 50, 75, 0.85, Map.of());
        ScoredPoi lunchSpot = scoredPoi("Bistro", PoiCategory.RESTAURANT, 400, 60, 0.88, Map.of());

        List<ScoredPoi> candidates = List.of(morningFort, lockedMuseum, afternoonPark, lunchSpot);
        TravelMatrix matrix = uniformMatrix(candidates, 10);

        PlannedStop locked = new PlannedStop(
                lockedMuseum.poi(), LocalTime.of(14, 0), LocalTime.of(15, 30),
                0, 600, true, SlotType.ACTIVITY
        );

        DayConstraints constraints = new DayConstraints(1, DayOfWeek.SATURDAY, TripPace.MODERATE, 2, 6000);
        List<PlannedStop> plan = optimizer.optimizeDay(candidates, matrix, constraints, List.of(locked));

        assertThat(plan).anyMatch(s -> s.locked() && s.poi().getName().equals("Locked Museum")
                && s.startTime().equals(LocalTime.of(14, 0)));

        for (PlannedStop stop : plan) {
            assertThat(stop.poi().isOpenDuring(DayOfWeek.SATURDAY, stop.startTime(), stop.endTime())).isTrue();
        }
    }

    @Test
    void twoOptReducesSuboptimalTravelTime() {
        Poi a = scoredPoi("A", PoiCategory.ATTRACTION, 100, 60, 0.9, Map.of()).poi();
        Poi b = scoredPoi("B", PoiCategory.ATTRACTION, 100, 60, 0.9, Map.of()).poi();
        Poi c = scoredPoi("C", PoiCategory.ATTRACTION, 100, 60, 0.9, Map.of()).poi();

        // A -> C -> B takes 5 + 5 = 10 mins, whereas A -> B -> C takes 40 + 5 = 45 mins
        Map<UUID, Integer> idx = Map.of(a.getId(), 0, b.getId(), 1, c.getId(), 2);
        int[][] m = {
                {0, 40, 5},
                {40, 0, 5},
                {5, 5, 0}
        };
        TravelMatrix matrix = new TravelMatrix(idx, m, "OSRM");
        DayConstraints constraints = new DayConstraints(1, DayOfWeek.MONDAY, TripPace.MODERATE, 1, 5000);

        List<PlannedStop> suboptimal = List.of(
                new PlannedStop(a, LocalTime.of(9, 0), LocalTime.of(10, 0), 0, 100, false, SlotType.ACTIVITY),
                new PlannedStop(b, LocalTime.of(10, 40), LocalTime.of(11, 40), 40, 100, false, SlotType.ACTIVITY),
                new PlannedStop(c, LocalTime.of(11, 45), LocalTime.of(12, 45), 5, 100, false, SlotType.ACTIVITY)
        );

        List<PlannedStop> refined = twoOptRefiner.refine(suboptimal, matrix, constraints);

        assertThat(twoOptRefiner.totalTravelMinutes(refined)).isLessThan(twoOptRefiner.totalTravelMinutes(suboptimal));
    }

    private ScoredPoi scoredPoi(
            String name, PoiCategory category, int costPerPerson, int durationMins,
            double score, Map<String, List<List<String>>> openingHours) {
        Poi poi = new Poi();
        poi.setId(UUID.randomUUID());
        poi.setName(name);
        poi.setCategory(category);
        poi.setEstimatedCost(costPerPerson);
        poi.setAvgDurationMins(durationMins);
        poi.setOpeningHours(openingHours);
        poi.setHoursEstimated(openingHours.isEmpty());
        poi.setLocation(GF.createPoint(new Coordinate(75.8, 26.9)));
        return new ScoredPoi(poi, score);
    }

    private TravelMatrix uniformMatrix(List<ScoredPoi> pois, int mins) {
        int n = pois.size();
        Map<UUID, Integer> idx = new HashMap<>();
        int[][] m = new int[n][n];
        for (int i = 0; i < n; i++) {
            idx.put(pois.get(i).poi().getId(), i);
            for (int j = 0; j < n; j++) {
                m[i][j] = (i == j) ? 0 : mins;
            }
        }
        return new TravelMatrix(idx, m, "OSRM");
    }
}
