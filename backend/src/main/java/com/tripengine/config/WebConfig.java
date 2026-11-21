package com.tripengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("externalRestClient")
    public RestClient externalRestClient(TripEngineProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.external().timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.external().timeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.external().userAgent())
                .build();
    }

    @Bean("osrmRestClient")
    public RestClient osrmRestClient(TripEngineProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.external().osrmTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.external().osrmTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.external().userAgent())
                .build();
    }

    @Bean("fastExternalRestClient")
    public RestClient fastExternalRestClient(TripEngineProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(3000));
        factory.setReadTimeout(Duration.ofMillis(4500));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.external().userAgent())
                .build();
    }
}
