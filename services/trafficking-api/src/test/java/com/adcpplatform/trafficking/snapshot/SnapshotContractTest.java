package com.adcpplatform.trafficking.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Reads the shared example snapshot (also decoded by the Go decision engine's tests) and writes it
 * back: a renamed or dropped field on either side changes the JSON and fails this test.
 */
class SnapshotContractTest {

    @Test
    void javaWireFormatMatchesSharedFixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String fixture = Files.readString(Path.of("../../contracts/fixtures/line-item-snapshot.example.json"));

        SnapshotJson parsed = mapper.readValue(fixture, SnapshotJson.class);
        JsonNode written = mapper.readTree(mapper.writeValueAsString(parsed));

        assertEquals(mapper.readTree(fixture), written);
    }
}
