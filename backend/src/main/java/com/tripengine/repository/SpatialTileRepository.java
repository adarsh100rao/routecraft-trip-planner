package com.tripengine.repository;

import com.tripengine.domain.entity.SpatialTile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SpatialTileRepository extends JpaRepository<SpatialTile, String> {
    List<SpatialTile> findByTileKeyInAndLastFetchedAtAfter(Collection<String> tileKeys, Instant threshold);
}
