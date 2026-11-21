package com.tripengine.domain.entity;

import com.tripengine.domain.enums.TripPace;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "destination", nullable = false)
    private String destination;

    @Column(name = "center_lat", nullable = false)
    private double centerLat;

    @Column(name = "center_lng", nullable = false)
    private double centerLng;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "days", nullable = false)
    private short days;

    @Column(name = "people_count", nullable = false)
    private short peopleCount;

    @Column(name = "total_budget", nullable = false)
    private int totalBudget;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "pace", nullable = false, length = 16)
    private TripPace pace;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences", columnDefinition = "jsonb", nullable = false)
    private List<String> preferences = new ArrayList<>();

    @Column(name = "estimated_total_cost", nullable = false)
    private int estimatedTotalCost;

    @Column(name = "routing_mode", nullable = false, length = 16)
    private String routingMode = "OSRM";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_polylines", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> routePolylines = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("dayNumber ASC, orderIndex ASC")
    private List<ItineraryStop> stops = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public double getCenterLat() { return centerLat; }
    public void setCenterLat(double centerLat) { this.centerLat = centerLat; }

    public double getCenterLng() { return centerLng; }
    public void setCenterLng(double centerLng) { this.centerLng = centerLng; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public short getDays() { return days; }
    public void setDays(short days) { this.days = days; }

    public short getPeopleCount() { return peopleCount; }
    public void setPeopleCount(short peopleCount) { this.peopleCount = peopleCount; }

    public int getTotalBudget() { return totalBudget; }
    public void setTotalBudget(int totalBudget) { this.totalBudget = totalBudget; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public TripPace getPace() { return pace; }
    public void setPace(TripPace pace) { this.pace = pace; }

    public List<String> getPreferences() { return preferences; }
    public void setPreferences(List<String> preferences) { this.preferences = preferences; }

    public int getEstimatedTotalCost() { return estimatedTotalCost; }
    public void setEstimatedTotalCost(int estimatedTotalCost) { this.estimatedTotalCost = estimatedTotalCost; }

    public String getRoutingMode() { return routingMode; }
    public void setRoutingMode(String routingMode) { this.routingMode = routingMode; }

    public Map<String, String> getRoutePolylines() { return routePolylines; }
    public void setRoutePolylines(Map<String, String> routePolylines) { this.routePolylines = routePolylines; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public List<ItineraryStop> getStops() { return stops; }
    public void setStops(List<ItineraryStop> stops) { this.stops = stops; }
}
