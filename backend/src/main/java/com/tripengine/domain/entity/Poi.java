package com.tripengine.domain.entity;

import com.tripengine.domain.enums.PoiCategory;
import com.tripengine.domain.model.TimeWindow;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;

@Entity
@Table(name = "pois")
public class Poi {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "external_id", nullable = false, unique = true, length = 128)
    private String externalId;

    @Column(name = "tile_key", nullable = false, length = 64)
    private String tileKey;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private PoiCategory category;

    @Column(name = "price_tier", nullable = false)
    private short priceTier = 2;

    @Column(name = "estimated_cost", nullable = false)
    private int estimatedCost;

    @Column(name = "rating", nullable = false)
    private double rating = 4.0;

    @Column(name = "avg_duration_mins", nullable = false)
    private int avgDurationMins = 60;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> tags = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opening_hours", columnDefinition = "jsonb", nullable = false)
    private Map<String, List<List<String>>> openingHours = new HashMap<>();

    @Column(name = "hours_estimated", nullable = false)
    private boolean hoursEstimated = true;

    @Column(name = "location", columnDefinition = "geography(Point,4326)", nullable = false)
    private Point location;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public double getLat() {
        return location != null ? location.getY() : 0.0;
    }

    public double getLng() {
        return location != null ? location.getX() : 0.0;
    }

    public List<TimeWindow> getTimeWindowsFor(DayOfWeek day) {
        String key = day.name().substring(0, 3).toLowerCase(Locale.ROOT);
        List<List<String>> raw = openingHours != null ? openingHours.get(key) : null;
        if (raw != null && !raw.isEmpty()) {
            List<TimeWindow> result = new ArrayList<>();
            for (List<String> pair : raw) {
                if (pair.size() == 2) {
                    try {
                        result.add(new TimeWindow(LocalTime.parse(pair.get(0)), LocalTime.parse(pair.get(1))));
                    } catch (Exception ignored) {
                        // Fall through to default if unparseable
                    }
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return defaultWindowsForCategory();
    }

    private List<TimeWindow> defaultWindowsForCategory() {
        return switch (category) {
            case RESTAURANT -> List.of(new TimeWindow(LocalTime.of(11, 0), LocalTime.of(22, 30)));
            case CAFE -> List.of(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(21, 0)));
            case NIGHTLIFE -> List.of(new TimeWindow(LocalTime.of(17, 0), LocalTime.of(23, 59)));
            case NATURE -> List.of(new TimeWindow(LocalTime.of(6, 0), LocalTime.of(19, 30)));
            default -> List.of(new TimeWindow(LocalTime.of(9, 0), LocalTime.of(19, 0)));
        };
    }

    public boolean isOpenDuring(DayOfWeek day, LocalTime start, LocalTime end) {
        return getTimeWindowsFor(day).stream().anyMatch(w -> w.contains(start, end));
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getTileKey() { return tileKey; }
    public void setTileKey(String tileKey) { this.tileKey = tileKey; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public PoiCategory getCategory() { return category; }
    public void setCategory(PoiCategory category) { this.category = category; }

    public short getPriceTier() { return priceTier; }
    public void setPriceTier(short priceTier) { this.priceTier = priceTier; }

    public int getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(int estimatedCost) { this.estimatedCost = estimatedCost; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public int getAvgDurationMins() { return avgDurationMins; }
    public void setAvgDurationMins(int avgDurationMins) { this.avgDurationMins = avgDurationMins; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Map<String, List<List<String>>> getOpeningHours() { return openingHours; }
    public void setOpeningHours(Map<String, List<List<String>>> openingHours) { this.openingHours = openingHours; }

    public boolean isHoursEstimated() { return hoursEstimated; }
    public void setHoursEstimated(boolean hoursEstimated) { this.hoursEstimated = hoursEstimated; }

    public Point getLocation() { return location; }
    public void setLocation(Point location) { this.location = location; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
