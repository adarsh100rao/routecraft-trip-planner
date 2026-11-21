package com.tripengine.repository;

import com.tripengine.domain.entity.ItineraryStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItineraryStopRepository extends JpaRepository<ItineraryStop, UUID> {
    Optional<ItineraryStop> findByIdAndTripId(UUID stopId, UUID tripId);
    List<ItineraryStop> findByTripIdAndDayNumberOrderByOrderIndexAsc(UUID tripId, short dayNumber);
}
