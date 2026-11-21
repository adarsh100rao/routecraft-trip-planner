package com.tripengine.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NeonDatabaseEnvironmentPostProcessorTest {

    @Test
    void parsesPooledNeonUriWithPrepareThresholdZero() {
        String raw = "postgresql://neondb_owner:secret123@ep-cool-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require";
        NeonDatabaseEnvironmentPostProcessor.ParsedJdbc parsed =
                NeonDatabaseEnvironmentPostProcessor.parsePostgresUri(raw, true);

        assertThat(parsed).isNotNull();
        assertThat(parsed.username()).isEqualTo("neondb_owner");
        assertThat(parsed.password()).isEqualTo("secret123");
        assertThat(parsed.jdbcUrl()).isEqualTo(
                "jdbc:postgresql://ep-cool-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require&channelBinding=require&reWriteBatchedInserts=true&prepareThreshold=0"
        );
    }

    @Test
    void parsesDirectUnpooledNeonUriWithoutPoolerFlag() {
        String raw = "postgresql://neondb_owner:secret123@ep-cool.us-east-2.aws.neon.tech/neondb?sslmode=require";
        NeonDatabaseEnvironmentPostProcessor.ParsedJdbc parsed =
                NeonDatabaseEnvironmentPostProcessor.parsePostgresUri(raw, false);

        assertThat(parsed).isNotNull();
        assertThat(parsed.jdbcUrl()).isEqualTo(
                "jdbc:postgresql://ep-cool.us-east-2.aws.neon.tech/neondb?sslmode=require&reWriteBatchedInserts=true"
        );
    }
}
