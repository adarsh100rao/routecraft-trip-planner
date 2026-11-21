package com.tripengine.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Polygon;

import java.time.Instant;

@Entity
@Table(name = "spatial_tiles")
public class SpatialTile {

    @Id
    @Column(name = "tile_key", length = 64)
    private String tileKey;

    @Column(name = "geom", columnDefinition = "geometry(Polygon,4326)", nullable = false)
    private Polygon geom;

    @Column(name = "last_fetched_at", nullable = false)
    private Instant lastFetchedAt;

    @Column(name = "poi_count", nullable = false)
    private int poiCount;

    public SpatialTile() {}

    public SpatialTile(String tileKey, Polygon geom, Instant lastFetchedAt, int poiCount) {
        this.tileKey = tileKey;
        this.geom = geom;
        this.lastFetchedAt = lastFetchedAt;
        this.poiCount = poiCount;
    }

    public String getTileKey() { return tileKey; }
    public void setTileKey(String tileKey) { this.tileKey = tileKey; }

    public Polygon getGeom() { return geom; }
    public void setGeom(Polygon geom) { this.geom = geom; }

    public Instant getLastFetchedAt() { return lastFetchedAt; }
    public void setLastFetchedAt(Instant lastFetchedAt) { this.lastFetchedAt = lastFetchedAt; }

    public int getPoiCount() { return poiCount; }
    public void setPoiCount(int poiCount) { this.poiCount = poiCount; }
}
