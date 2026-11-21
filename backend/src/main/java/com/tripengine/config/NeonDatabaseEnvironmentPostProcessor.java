package com.tripengine.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Automatically wires Neon Postgres libpq URIs into Spring Boot JDBC & Flyway properties:
 * - DATABASE_URL (pooled -pooler endpoint) -> spring.datasource.* with prepareThreshold=0 for PgBouncer transaction mode
 * - DATABASE_URL_UNPOOLED (direct endpoint) -> spring.flyway.* so DDL/migrations never run through PgBouncer
 */
public class NeonDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> overrides = new LinkedHashMap<>();

        String pooledUri = environment.getProperty("DATABASE_URL");
        if (pooledUri != null && !pooledUri.isBlank()) {
            ParsedJdbc pooled = parsePostgresUri(pooledUri, true);
            if (pooled != null) {
                overrides.put("spring.datasource.url", pooled.jdbcUrl());
                if (pooled.username() != null) overrides.put("spring.datasource.username", pooled.username());
                if (pooled.password() != null) overrides.put("spring.datasource.password", pooled.password());
            }
        }

        String unpooledUri = environment.getProperty("DATABASE_URL_UNPOOLED");
        if (unpooledUri != null && !unpooledUri.isBlank()) {
            ParsedJdbc direct = parsePostgresUri(unpooledUri, false);
            if (direct != null) {
                overrides.put("spring.flyway.url", direct.jdbcUrl());
                if (direct.username() != null) overrides.put("spring.flyway.user", direct.username());
                if (direct.password() != null) overrides.put("spring.flyway.password", direct.password());
            }
        }

        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("neonDatabaseProperties", overrides));
        }
    }

    public record ParsedJdbc(String jdbcUrl, String username, String password) {}

    public static ParsedJdbc parsePostgresUri(String rawUri, boolean isPooled) {
        String trimmed = rawUri.trim();
        if (trimmed.startsWith("jdbc:")) {
            return new ParsedJdbc(trimmed, null, null);
        }
        if (!trimmed.startsWith("postgres://") && !trimmed.startsWith("postgresql://")) {
            return null;
        }

        URI uri = URI.create(trimmed);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        int port = uri.getPort();
        String path = uri.getPath();
        String dbName = (path != null && path.startsWith("/")) ? path.substring(1) : (path != null ? path : "");

        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                username = URLDecoder.decode(userInfo.substring(0, colon), StandardCharsets.UTF_8);
                password = URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8);
            } else {
                username = URLDecoder.decode(userInfo, StandardCharsets.UTF_8);
            }
        }

        // Preserve compatible query params; translate libpq channel_binding & disable server-side prepared statements on pooler
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq);
                    String val = pair.substring(eq + 1);
                    if ("channel_binding".equals(key)) {
                        params.put("channelBinding", val);
                    } else {
                        params.put(key, val);
                    }
                }
            }
        }
        params.putIfAbsent("sslmode", "require");
        params.putIfAbsent("reWriteBatchedInserts", "true");
        if (isPooled || host.contains("-pooler")) {
            params.putIfAbsent("prepareThreshold", "0");
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(host);
        if (port > 0) {
            jdbc.append(':').append(port);
        }
        jdbc.append('/').append(dbName);
        if (!params.isEmpty()) {
            jdbc.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) jdbc.append('&');
                jdbc.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }
        return new ParsedJdbc(jdbc.toString(), username, password);
    }
}
