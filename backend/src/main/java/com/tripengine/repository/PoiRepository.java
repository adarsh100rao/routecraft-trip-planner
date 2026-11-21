package com.tripengine.repository;

import com.tripengine.domain.entity.Poi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PoiRepository extends JpaRepository<Poi, UUID> {

    Optional<Poi> findByExternalId(String externalId);

    List<Poi> findByExternalIdIn(Collection<String> externalIds);

    @Query(value = """
            SELECT * FROM pois
            WHERE ST_DWithin(
                location,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                :radiusMeters
            )
            ORDER BY rating DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Poi> findWithinRadius(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit") int limit
    );
}
