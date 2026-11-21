package com.tripengine.service.optimizer;

import com.tripengine.domain.model.DayConstraints;
import com.tripengine.domain.model.PlannedStop;
import com.tripengine.domain.model.TravelMatrix;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 2-Opt local search refiner that swaps unlocked, slot-compatible stops
 * whenever it reduces total travel time while preserving opening-hour windows.
 */
@Component
public class TwoOptRefiner {

    public List<PlannedStop> refine(
            List<PlannedStop> initial,
            TravelMatrix matrix,
            DayConstraints constraints) {
        if (initial.size() < 3) {
            return initial;
        }

        List<PlannedStop> best = new ArrayList<>(initial);
        int bestTravel = totalTravelMinutes(best);
        boolean improved = true;

        while (improved) {
            improved = false;
            for (int i = 0; i < best.size() - 1 && !improved; i++) {
                if (best.get(i).locked()) continue;
                for (int j = i + 1; j < best.size(); j++) {
                    if (best.get(j).locked()) continue;
                    // Only swap compatible slot types so Lunch stays at lunchtime and Dinner at dinnertime
                    if (best.get(i).slotType() != best.get(j).slotType()) continue;

                    List<PlannedStop> candidate = new ArrayList<>(best);
                    PlannedStop tmp = candidate.get(i);
                    candidate.set(i, candidate.get(j));
                    candidate.set(j, tmp);

                    List<PlannedStop> rescheduled = recalculateSchedule(candidate, matrix, constraints);
                    if (rescheduled != null) {
                        int newTravel = totalTravelMinutes(rescheduled);
                        if (newTravel < bestTravel) {
                            best = rescheduled;
                            bestTravel = newTravel;
                            improved = true;
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }

    private List<PlannedStop> recalculateSchedule(
            List<PlannedStop> ordered,
            TravelMatrix matrix,
            DayConstraints constraints) {
        List<PlannedStop> result = new ArrayList<>(ordered.size());
        LocalTime cursor = constraints.pace().getDayStart();
        LocalTime dayEnd = constraints.pace().getDayEnd();
        PlannedStop prev = null;

        for (PlannedStop stop : ordered) {
            int travelMins = prev == null ? 0 : matrix.travelMinutes(prev.poi().getId(), stop.poi().getId());
            LocalTime arrival = cursor.plusMinutes(travelMins);

            if (stop.locked()) {
                if (arrival.isAfter(stop.startTime())) {
                    return null; // Cannot reach locked stop in time
                }
                arrival = stop.startTime();
            }

            LocalTime departure = arrival.plusMinutes(stop.poi().getAvgDurationMins());
            if (departure.isBefore(arrival) || departure.isAfter(dayEnd)) {
                return null;
            }
            if (!stop.poi().isOpenDuring(constraints.dayOfWeek(), arrival, departure)) {
                return null;
            }

            PlannedStop updated = new PlannedStop(
                    stop.poi(), arrival, departure, travelMins,
                    stop.estimatedGroupCost(), stop.locked(), stop.slotType()
            );
            result.add(updated);
            cursor = departure;
            prev = updated;
        }
        return result;
    }

    public int totalTravelMinutes(List<PlannedStop> stops) {
        return stops.stream().mapToInt(PlannedStop::travelMinsFromPrev).sum();
    }
}
