package com.tripengine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages SSE emitters per generation jobId:
 * - Buffers events emitted before the client attaches EventSource
 * - Sends periodic keep-alive comments every 5s so Cloudflare/Render/mobile proxies never drop idle streams
 * - Retains JobStatus snapshots for 10 minutes so clients can seamlessly fall back to HTTP polling
 */
@Component
public class SseJobRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseJobRegistry.class);
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private record BufferedEvent(String eventName, Object payload, boolean terminal) {}

    public record JobStatus(
            String jobId,
            String status, // RUNNING, COMPLETED, FAILED
            Object lastStage,
            Object result,
            String error,
            Instant updatedAt
    ) {}

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<BufferedEvent>> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JobStatus> jobStatuses = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SseJobRegistry() {
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeatsAndPrune, 5, 5, TimeUnit.SECONDS);
    }

    public void registerJob(String jobId) {
        buffers.put(jobId, new CopyOnWriteArrayList<>());
        jobStatuses.put(jobId, new JobStatus(jobId, "RUNNING", null, null, null, Instant.now()));
    }

    public JobStatus getJobStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

    public SseEmitter attachEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(jobId, emitter);

        emitter.onCompletion(() -> detachEmitter(jobId));
        emitter.onTimeout(() -> detachEmitter(jobId));
        emitter.onError(ex -> detachEmitter(jobId));

        List<BufferedEvent> backlog = buffers.get(jobId);
        if (backlog != null) {
            for (BufferedEvent evt : backlog) {
                if (!sendSafe(jobId, emitter, evt)) {
                    break;
                }
            }
        }
        return emitter;
    }

    public void emit(String jobId, String eventName, Object payload) {
        jobStatuses.put(jobId, new JobStatus(jobId, "RUNNING", payload, null, null, Instant.now()));
        dispatch(jobId, new BufferedEvent(eventName, payload, false));
    }

    public void complete(String jobId, String eventName, Object payload) {
        jobStatuses.put(jobId, new JobStatus(jobId, "COMPLETED", null, payload, null, Instant.now()));
        dispatch(jobId, new BufferedEvent(eventName, payload, true));
    }

    public void fail(String jobId, String errorMessage) {
        jobStatuses.put(jobId, new JobStatus(jobId, "FAILED", null, null, errorMessage, Instant.now()));
        dispatch(jobId, new BufferedEvent("ERROR", Map.of("message", errorMessage), true));
    }

    private void dispatch(String jobId, BufferedEvent event) {
        List<BufferedEvent> backlog = buffers.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        backlog.add(event);

        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            sendSafe(jobId, emitter, event);
        }
    }

    private boolean sendSafe(String jobId, SseEmitter emitter, BufferedEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.eventName()).data(event.payload()));
            if (event.terminal()) {
                emitter.complete();
                detachEmitter(jobId);
                buffers.remove(jobId);
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE client disconnected for job {}: {}", jobId, e.getMessage());
            detachEmitter(jobId);
            return false;
        }
    }

    private void sendHeartbeatsAndPrune() {
        emitters.forEach((jobId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                detachEmitter(jobId);
            }
        });

        Instant cutoff = Instant.now().minusSeconds(600);
        jobStatuses.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(cutoff));
    }

    private void detachEmitter(String jobId) {
        emitters.remove(jobId);
    }
}
