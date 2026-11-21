package com.tripengine.dto.response;

import java.util.Map;

public record SseProgressEvent(
        String stage,
        String message,
        int progress,
        Map<String, Object> metadata
) {
    public static SseProgressEvent of(String stage, String message, int progress) {
        return new SseProgressEvent(stage, message, progress, Map.of());
    }

    public static SseProgressEvent of(String stage, String message, int progress, Map<String, Object> metadata) {
        return new SseProgressEvent(stage, message, progress, metadata);
    }
}
