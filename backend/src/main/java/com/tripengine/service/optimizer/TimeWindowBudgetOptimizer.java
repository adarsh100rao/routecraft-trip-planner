package com.tripengine.service.optimizer;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.SlotType;
import com.tripengine.domain.model.DayConstraints;
import com.tripengine.domain.model.PlannedStop;
import com.tripengine.domain.model.ScoredPoi;
import com.tripengine.domain.model.TravelMatrix;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.*;

/**
 * Time-Window & Budget Constrained Heuristic Optimizer.
 * Phase 1: Slot-based greedy construction respecting opening hours, meal windows, locked stops, and budget.
 * Phase 2: 2-Opt local search refinement to minimize total travel time.
 */
@Service
public class TimeWindowBudgetOptimizer implements ItineraryOptimizer {

    private static final LocalTime LUNCH_START = LocalTime.of(12, 0);
    private static final LocalTime LUNCH_END = LocalTime.of(14, 30);
    private static final LocalTime DINNER_START = LocalTime.of(18, 30);
    private static final LocalTime DINNER_END = LocalTime.of(21, 0);

    private static final double TRAVEL_PENALTY_WEIGHT = 0.04;
    private static final double COST_PENALTY_WEIGHT = 0.35;

    private final TwoOptRefiner twoOptRefiner;
    private final Timer optimizerTimer;

    public TimeWindowBudgetOptimizer(TwoOptRefiner twoOptRefiner, MeterRegistry meterRegistry) {
        this.twoOptRefiner = twoOptRefiner;
        this.optimizerTimer = Timer.builder("trip.optimizer.duration")
                .description("Time-window & budget optimizer execution duration")
                .register(meterRegistry);
    }

    @Override
    public List<PlannedStop> optimizeDay(
            List<ScoredPoi> candidates,
            TravelMatrix matrix,
            DayConstraints constraints,
            List<PlannedStop> lockedStops) {

        return optimizerTimer.record(() -> {
            List<PlannedStop> locked = new ArrayList<>(lockedStops != null ? lockedStops : List.of());
            locked.sort(Comparator.comparing(PlannedStop::startTime));

            Set<UUID> usedPoiIds = new HashSet<>();
            int remainingBudget = constraints.dailyBudget();
            boolean lunchScheduled = false;
            boolean dinnerScheduled = false;

            for (PlannedStop ls : locked) {
                usedPoiIds.add(ls.poi().getId());
                remainingBudget = Math.max(0, remainingBudget - ls.estimatedGroupCost());
                if (ls.slotType() == SlotType.LUNCH) lunchScheduled = true;
                if (ls.slotType() == SlotType.DINNER) dinnerScheduled = true;
            }

            List<PlannedStop> schedule = new ArrayList<>();
            LocalTime currentTime = constraints.pace().getDayStart();
            LocalTime dayEnd = constraints.pace().getDayEnd();
            int maxStops = constraints.pace().getMaxStopsPerDay();
            Poi lastPoi = null;
            int lockedCursor = 0;

            while (schedule.size() < maxStops && currentTime.isBefore(dayEnd)) {
                // If next locked stop starts soon, emit it directly
                if (lockedCursor < locked.size()) {
                    PlannedStop nextLocked = locked.get(lockedCursor);
                    int travelToLocked = lastPoi == null ? 0
                            : matrix.travelMinutes(lastPoi.getId(), nextLocked.poi().getId());
                    if (!currentTime.plusMinutes(travelToLocked + 45).isBefore(nextLocked.startTime())) {
                        PlannedStop placed = new PlannedStop(
                                nextLocked.poi(),
                                nextLocked.startTime(),
                                nextLocked.endTime(),
                                travelToLocked,
                                nextLocked.estimatedGroupCost(),
                                true,
                                nextLocked.slotType()
                        );
                        schedule.add(placed);
                        currentTime = placed.endTime();
                        lastPoi = placed.poi();
                        lockedCursor++;
                        continue;
                    }
                }

                SlotType desiredSlot = resolveSlotType(currentTime, lunchScheduled, dinnerScheduled);
                LocalTime upperBound = lockedCursor < locked.size()
                        ? locked.get(lockedCursor).startTime()
                        : dayEnd;

                PlannedStop bestStop = selectBestCandidate(
                        candidates, usedPoiIds, lastPoi, currentTime, upperBound,
                        remainingBudget, desiredSlot, matrix, constraints
                );

                // Fallback: if no food spot fits a meal window, try any activity so the day stays productive
                if (bestStop == null && (desiredSlot == SlotType.LUNCH || desiredSlot == SlotType.DINNER)) {
                    bestStop = selectBestCandidate(
                            candidates, usedPoiIds, lastPoi, currentTime, upperBound,
                            remainingBudget, SlotType.ACTIVITY, matrix, constraints
                    );
                }

                // Fallback: if no activity fits right now, advance to next meal window if available
                if (bestStop == null && desiredSlot == SlotType.ACTIVITY) {
                    if (!lunchScheduled && currentTime.isBefore(LUNCH_START) && LUNCH_START.isBefore(upperBound)) {
                        currentTime = LUNCH_START;
                        continue;
                    }
                    if (!dinnerScheduled && currentTime.isBefore(DINNER_START) && DINNER_START.isBefore(upperBound)) {
                        currentTime = DINNER_START;
                        continue;
                    }
                }

                if (bestStop == null) {
                    if (lockedCursor < locked.size()) {
                        // Jump forward to next locked stop
                        currentTime = locked.get(lockedCursor).startTime();
                        continue;
                    }
                    break;
                }

                schedule.add(bestStop);
                usedPoiIds.add(bestStop.poi().getId());
                remainingBudget = Math.max(0, remainingBudget - bestStop.estimatedGroupCost());
                if (bestStop.slotType() == SlotType.LUNCH) lunchScheduled = true;
                if (bestStop.slotType() == SlotType.DINNER) dinnerScheduled = true;
                currentTime = bestStop.endTime();
                lastPoi = bestStop.poi();
            }

            // Append any remaining locked stops not yet placed
            while (lockedCursor < locked.size()) {
                PlannedStop nextLocked = locked.get(lockedCursor++);
                int travel = lastPoi == null ? 0 : matrix.travelMinutes(lastPoi.getId(), nextLocked.poi().getId());
                schedule.add(new PlannedStop(
                        nextLocked.poi(), nextLocked.startTime(), nextLocked.endTime(),
                        travel, nextLocked.estimatedGroupCost(), true, nextLocked.slotType()
                ));
                lastPoi = nextLocked.poi();
            }

            return twoOptRefiner.refine(schedule, matrix, constraints);
        });
    }

    private SlotType resolveSlotType(LocalTime currentTime, boolean lunchDone, boolean dinnerDone) {
        if (!lunchDone && !currentTime.isBefore(LUNCH_START) && !currentTime.isAfter(LUNCH_END)) {
            return SlotType.LUNCH;
        }
        if (!dinnerDone && !currentTime.isBefore(DINNER_START) && !currentTime.isAfter(DINNER_END)) {
            return SlotType.DINNER;
        }
        return SlotType.ACTIVITY;
    }

    private PlannedStop selectBestCandidate(
            List<ScoredPoi> candidates,
            Set<UUID> usedPoiIds,
            Poi lastPoi,
            LocalTime currentTime,
            LocalTime upperBound,
            int remainingBudget,
            SlotType desiredSlot,
            TravelMatrix matrix,
            DayConstraints constraints) {

        ScoredPoi best = null;
        double bestUtility = -1.0;
        LocalTime bestArrival = null;
        LocalTime bestDeparture = null;
        int bestTravel = 0;
        int bestGroupCost = 0;

        for (ScoredPoi candidate : candidates) {
            Poi poi = candidate.poi();
            if (usedPoiIds.contains(poi.getId())) continue;
            if (!matchesSlot(poi, desiredSlot)) continue;

            int groupCost = poi.getEstimatedCost() * constraints.peopleCount();
            if (groupCost > remainingBudget) continue;

            int travelMins = lastPoi == null ? 0 : matrix.travelMinutes(lastPoi.getId(), poi.getId());
            LocalTime arrival = currentTime.plusMinutes(travelMins);
            LocalTime departure = arrival.plusMinutes(poi.getAvgDurationMins());

            if (arrival.isBefore(currentTime) || departure.isBefore(arrival) || departure.isAfter(upperBound)) {
                continue;
            }
            if (!poi.isOpenDuring(constraints.dayOfWeek(), arrival, departure)) {
                continue;
            }

            double costRatio = remainingBudget > 0 ? (double) groupCost / remainingBudget : 1.0;
            // Only apply cost compression penalty when budget is tight (single stop > 20% of remaining daily budget)
            double costPenalty = costRatio > 0.20 ? COST_PENALTY_WEIGHT * (costRatio - 0.20) : 0.0;
            double utility = candidate.score()
                    / (1.0 + TRAVEL_PENALTY_WEIGHT * travelMins + costPenalty);

            if (utility > bestUtility) {
                bestUtility = utility;
                best = candidate;
                bestArrival = arrival;
                bestDeparture = departure;
                bestTravel = travelMins;
                bestGroupCost = groupCost;
            }
        }

        if (best == null) {
            return null;
        }
        return new PlannedStop(
                best.poi(), bestArrival, bestDeparture, bestTravel, bestGroupCost, false, desiredSlot
        );
    }

    private boolean matchesSlot(Poi poi, SlotType slotType) {
        return switch (slotType) {
            case LUNCH, DINNER, CAFE -> poi.getCategory().isFood();
            case ACTIVITY -> !poi.getCategory().isFood();
        };
    }
}
