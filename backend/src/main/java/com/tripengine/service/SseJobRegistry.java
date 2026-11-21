package com.tripengine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages SSE emitters per generation jobId, buffering events emitted before
 * the frontend establishes its EventSource connection so no stage update is ever missed.
 */
@Component
public class SseJobRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseJobRegistry.class);
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private record BufferedEvent(String eventName, Object payload, boolean terminal) {}

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<BufferedEvent>> buffers = new ConcurrentHashMap<>();

    public void registerJob(String jobId) {
        buffers.put(jobId, new CopyOnWriteArrayList<>());
    }

    public SseEmitter attachEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(jobId, emitter);

        emitter.onCompletion(() -> cleanup(jobId));
        emitter.onTimeout(() -> cleanup(jobId));
        emitter.onError(ex -> cleanup(jobId));

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
        dispatch(jobId, new BufferedEvent(eventName, payload, false));
    }

    public void complete(String jobId, String eventName, Object payload) {
        dispatch(jobId, new BufferedEvent(eventName, payload, true));
    }

    public void fail(String jobId, String errorMessage) {
        dispatch(jobId, new BufferedEvent("ERROR", java.util.Map.of("message", errorMessage), true));
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
                cleanup(jobId);
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE client disconnected for job {}: {}", jobId, e.getMessage());
            cleanup(jobId);
            return false;
        }
    }

    private void cleanup(String jobId) {
        emitters.remove(jobId);
        buffers.remove(jobId);
    }
}
