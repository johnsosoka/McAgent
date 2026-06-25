package com.mcagent.core.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RunLog}.
 *
 * <p>Uses the package-private {@code RunLog(Counters, Path)} constructor to
 * write the log file into a {@link TempDir} rather than the user's home directory.</p>
 */
class RunLogTest {

    @TempDir
    Path tempDir;

    private RunLog runLog;
    private Counters counters;

    @BeforeEach
    void setUp() {
        counters = new Counters();
        runLog = new RunLog(counters, tempDir);
        runLog.start();
    }

    @AfterEach
    void tearDown() {
        if (runLog != null) {
            runLog.stop();
            runLog = null;
        }
    }

    @Test
    void start_createsLogFileInSpecifiedDirectory() {
        Path logFile = runLog.currentLogFile();
        assertThat(logFile).isNotNull();
        assertThat(logFile.getParent()).isEqualTo(tempDir);
    }

    @Test
    void log_writesJsonlLineToFile() throws IOException, InterruptedException {
        runLog.log("test_event", Map.of("key", "value", "count", 42));

        // Give the async writer time to flush.
        Thread.sleep(300);

        Path logFile = runLog.currentLogFile();
        assertThat(logFile).isNotNull();
        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(1);

        JsonNode node = new ObjectMapper().readTree(lines.get(0));
        assertThat(node.get("event").asText()).isEqualTo("test_event");
        assertThat(node.get("ts")).isNotNull();
        assertThat(node.get("key").asText()).isEqualTo("value");
        assertThat(node.get("count").asInt()).isEqualTo(42);
    }

    @Test
    void log_writes100Events_allPresentAsValidJson() throws IOException, InterruptedException {
        int eventCount = 100;
        for (int i = 0; i < eventCount; i++) {
            runLog.log("batch_event", Map.of("index", i));
        }

        // Allow async writer to drain.
        Thread.sleep(500);

        Path logFile = runLog.currentLogFile();
        assertThat(logFile).isNotNull();
        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(eventCount);

        ObjectMapper mapper = new ObjectMapper();
        for (String line : lines) {
            JsonNode node = mapper.readTree(line);
            assertThat(node.get("event").asText()).isEqualTo("batch_event");
            assertThat(node.get("ts")).isNotNull();
        }
    }

    @Test
    void log_includesCmdIdFromMdcWhenSet() throws IOException, InterruptedException {
        try (var scope = CorrelationId.scope("abc123")) {
            runLog.log("mdc_event", Map.of());
        }

        Thread.sleep(300);

        Path logFile = runLog.currentLogFile();
        assertThat(logFile).isNotNull();
        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(1);

        JsonNode node = new ObjectMapper().readTree(lines.get(0));
        assertThat(node.get("cmd_id").asText()).isEqualTo("abc123");
    }

    @Test
    void log_eventTypeContainingQuotes_producesValidJson() throws IOException, InterruptedException {
        // An event type with embedded quotes would break a hand-rolled JSON string.
        // Verify the resulting JSONL line is still parseable.
        String eventType = "evt\"with\\\"quotes\"";
        runLog.log(eventType, Map.of());

        Thread.sleep(300);

        Path logFile = runLog.currentLogFile();
        assertThat(logFile).isNotNull();
        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(1);

        // Will throw if the line is not valid JSON.
        JsonNode node = new ObjectMapper().readTree(lines.get(0));
        assertThat(node.get("event").asText()).isEqualTo(eventType);
    }

    @Test
    void log_afterStop_isNoOp() throws InterruptedException, IOException {
        runLog.stop();
        // Should not throw and should not write.
        runLog.log("should_not_appear", Map.of());
        Thread.sleep(200);
        runLog = null; // prevent double-stop in tearDown

        // If the file was created before stop, it must be empty.
        // If it was never created, that's also fine.
        Path logFile = Files.list(tempDir).findFirst().orElse(null);
        if (logFile != null) {
            List<String> lines = Files.readAllLines(logFile);
            assertThat(lines).isEmpty();
        }
    }
}
