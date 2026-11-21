package com.tripengine.repository;

import com.tripengine.domain.entity.Trip;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    @EntityGraph(attributePaths = {"stops", "stops.poi"})
    Optional<Trip> findWithStopsById(UUID id);
}
