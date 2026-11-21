package com.tripengine.dto.request;

import com.tripengine.domain.enums.InterestTag;
import com.tripengine.domain.enums.TripPace;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public record GenerateTripRequest(
        @NotBlank(message = "Destination is required")
        String destination,

        LocalDate startDate,

        @Min(value = 1, message = "Days must be at least 1")
        @Max(value = 7, message = "Days cannot exceed 7")
        int days,

        @Min(value = 1, message = "At least 1 person required")
        @Max(value = 20, message = "Maximum 20 people supported")
        int peopleCount,

        @Min(value = 500, message = "Budget must be positive")
        int totalBudget,

        String currency,

        TripPace pace,

        @NotEmpty(message = "Select at least one interest")
        List<InterestTag> interests
) {
    public LocalDate effectiveStartDate() {
        return startDate != null ? startDate : LocalDate.now().plusDays(1);
    }

    public String effectiveCurrency() {
        return (currency == null || currency.isBlank()) ? "INR" : currency.toUpperCase();
    }

    public TripPace effectivePace() {
        return pace != null ? pace : TripPace.MODERATE;
    }
}
