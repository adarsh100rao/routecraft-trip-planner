package com.tripengine.service;

import com.tripengine.domain.entity.ItineraryStop;
import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.entity.Trip;
import com.tripengine.dto.response.DayPlanResponse;
import com.tripengine.dto.response.StopResponse;
import com.tripengine.dto.response.TripResponse;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class TripMapper {

    public TripResponse toTripResponse(Trip trip, boolean cacheHit, long generationTimeMs) {
        Map<Integer, List<ItineraryStop>> byDay = trip.getStops().stream()
                .collect(Collectors.groupingBy(s -> (int) s.getDayNumber()));

        List<DayPlanResponse> dayPlans = new ArrayList<>();
        int foodCost = 0;
        int activityCost = 0;

        for (int day = 1; day <= trip.getDays(); day++) {
            List<ItineraryStop> stops = byDay.getOrDefault(day, List.of()).stream()
                    .sorted(Comparator.comparingInt(ItineraryStop::getOrderIndex))
                    .toList();

            int dayCost = 0;
            int dayTravel = 0;
            List<StopResponse> stopResponses = new ArrayList<>(stops.size());

            for (ItineraryStop s : stops) {
                dayCost += s.getEstimatedCost();
                dayTravel += s.getTravelMinsFromPrev();
                if (s.getPoi().getCategory().isFood()) {
                    foodCost += s.getEstimatedCost();
                } else {
                    activityCost += s.getEstimatedCost();
                }
                stopResponses.add(toStopResponse(s));
            }

            String polyline = trip.getRoutePolylines() != null
                    ? trip.getRoutePolylines().getOrDefault(String.valueOf(day), "")
                    : "";

            dayPlans.add(new DayPlanResponse(
                    day,
                    trip.getStartDate().plusDays(day - 1L),
                    dayCost,
                    dayTravel,
                    polyline,
                    stopResponses
            ));
        }

        return new TripResponse(
                trip.getId(),
                trip.getDestination(),
                trip.getCenterLat(),
                trip.getCenterLng(),
                trip.getStartDate(),
                trip.getDays(),
                trip.getPeopleCount(),
                trip.getTotalBudget(),
                trip.getCurrency(),
                trip.getPace(),
                trip.getPreferences(),
                trip.getEstimatedTotalCost(),
                foodCost,
                activityCost,
                trip.getRoutingMode(),
                cacheHit,
                generationTimeMs,
                trip.getCreatedAt(),
                dayPlans
        );
    }

    public DayPlanResponse toDayPlanResponse(Trip trip, int dayNumber, List<ItineraryStop> stops, String polyline) {
        List<ItineraryStop> sorted = stops.stream()
                .sorted(Comparator.comparingInt(ItineraryStop::getOrderIndex))
                .toList();
        int dayCost = sorted.stream().mapToInt(ItineraryStop::getEstimatedCost).sum();
        int dayTravel = sorted.stream().mapToInt(ItineraryStop::getTravelMinsFromPrev).sum();
        List<StopResponse> responses = sorted.stream().map(this::toStopResponse).toList();

        return new DayPlanResponse(
                dayNumber,
                trip.getStartDate().plusDays(dayNumber - 1L),
                dayCost,
                dayTravel,
                polyline != null ? polyline : "",
                responses
        );
    }

    public StopResponse toStopResponse(ItineraryStop stop) {
        Poi poi = stop.getPoi();
        return new StopResponse(
                stop.getId(),
                poi.getId(),
                poi.getName(),
                poi.getCategory(),
                stop.getSlotType(),
                stop.getOrderIndex(),
                stop.getStartTime(),
                stop.getEndTime(),
                stop.getTravelMinsFromPrev(),
                stop.getEstimatedCost(),
                poi.getPriceTier(),
                poi.getRating(),
                poi.getLat(),
                poi.getLng(),
                stop.isLocked(),
                poi.isHoursEstimated(),
                poi.getTags()
        );
    }
}
