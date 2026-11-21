package com.tripengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripengine")
public record TripEngineProperties(Tile tile, External external) {
    public record Tile(
            double gridSizeDegrees,
            int ttlDays,
            double defaultSearchRadiusMeters,
            double expandedSearchRadiusMeters
    ) {}

    public record External(
            String nominatimBaseUrl,
            String overpassBaseUrl,
            String osrmBaseUrl,
            String geminiApiKey,
            String geminiModel,
            String userAgent,
            int timeoutMs,
            int osrmTimeoutMs
    ) {}
}
