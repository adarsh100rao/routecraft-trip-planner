package com.tripengine.controller;

import com.tripengine.dto.request.GenerateTripRequest;
import com.tripengine.dto.request.ReoptimizeDayRequest;
import com.tripengine.dto.request.UpdateStopRequest;
import com.tripengine.dto.response.DayPlanResponse;
import com.tripengine.dto.response.GenerateJobResponse;
import com.tripengine.dto.response.StopResponse;
import com.tripengine.dto.response.TripResponse;
import com.tripengine.service.SseJobRegistry;
import com.tripengine.service.TripGenerationOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripGenerationOrchestrator orchestrator;
    private final SseJobRegistry sseJobRegistry;

    public TripController(TripGenerationOrchestrator orchestrator, SseJobRegistry sseJobRegistry) {
        this.orchestrator = orchestrator;
        this.sseJobRegistry = sseJobRegistry;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerateJobResponse generateTrip(@Valid @RequestBody GenerateTripRequest request) {
        String jobId = orchestrator.submitGenerationJob(request);
        return new GenerateJobResponse(jobId, "/api/v1/trips/jobs/" + jobId + "/stream");
    }

    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable String jobId) {
        return sseJobRegistry.attachEmitter(jobId);
    }

    @GetMapping("/jobs/{jobId}")
    public SseJobRegistry.JobStatus getJobStatus(@PathVariable String jobId) {
        SseJobRegistry.JobStatus status = sseJobRegistry.getJobStatus(jobId);
        if (status == null) {
            throw new java.util.NoSuchElementException("Job not found: " + jobId);
        }
        return status;
    }

    @GetMapping("/{tripId}")
    public TripResponse getTrip(@PathVariable UUID tripId) {
        return orchestrator.getTrip(tripId);
    }

    @PatchMapping("/{tripId}/stops/{stopId}")
    public StopResponse updateStop(
            @PathVariable UUID tripId,
            @PathVariable UUID stopId,
            @RequestBody UpdateStopRequest request) {
        return orchestrator.updateStop(tripId, stopId, request);
    }

    @DeleteMapping("/{tripId}/stops/{stopId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStop(@PathVariable UUID tripId, @PathVariable UUID stopId) {
        orchestrator.deleteStop(tripId, stopId);
    }

    @PostMapping("/{tripId}/days/{dayNumber}/reoptimize")
    public DayPlanResponse reoptimizeDay(
            @PathVariable UUID tripId,
            @PathVariable int dayNumber,
            @RequestBody(required = false) ReoptimizeDayRequest request) {
        return orchestrator.reoptimizeDay(tripId, dayNumber, request);
    }
}
