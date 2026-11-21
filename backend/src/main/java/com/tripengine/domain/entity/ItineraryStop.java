package com.tripengine.domain.entity;

import com.tripengine.domain.enums.SlotType;
import jakarta.persistence.*;

import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "itinerary_stops")
public class ItineraryStop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "poi_id", nullable = false)
    private Poi poi;

    @Column(name = "day_number", nullable = false)
    private short dayNumber;

    @Column(name = "order_index", nullable = false)
    private short orderIndex;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "travel_mins_from_prev", nullable = false)
    private int travelMinsFromPrev;

    @Column(name = "estimated_cost", nullable = false)
    private int estimatedCost;

    @Column(name = "is_locked", nullable = false)
    private boolean locked;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_type", nullable = false, length = 16)
    private SlotType slotType;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }

    public Poi getPoi() { return poi; }
    public void setPoi(Poi poi) { this.poi = poi; }

    public short getDayNumber() { return dayNumber; }
    public void setDayNumber(short dayNumber) { this.dayNumber = dayNumber; }

    public short getOrderIndex() { return orderIndex; }
    public void setOrderIndex(short orderIndex) { this.orderIndex = orderIndex; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public int getTravelMinsFromPrev() { return travelMinsFromPrev; }
    public void setTravelMinsFromPrev(int travelMinsFromPrev) { this.travelMinsFromPrev = travelMinsFromPrev; }

    public int getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(int estimatedCost) { this.estimatedCost = estimatedCost; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public SlotType getSlotType() { return slotType; }
    public void setSlotType(SlotType slotType) { this.slotType = slotType; }
}
