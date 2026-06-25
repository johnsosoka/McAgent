package com.mcagent.core.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Structured JSONL run log for long-running bot sessions.
 *
 * <p>Each event is serialized to one JSON line and written to a per-session file.
 * The file path is determined at startup:</p>
 * <ol>
 *   <li>If {@code MC_AGENT_RUN_LOG_DIR} environment variable is set, use that directory.</li>
 *   <li>On macOS, default to {@code ~/Library/Application Support/minecraft/logs/mc-agent/}.</li>
 *   <li>Otherwise fall back to {@code ${user.home}/.mc-agent/logs/}.</li>
 * </ol>
 *
 * <p>Writing is fully asynchronous: callers enqueue a string and a daemon thread
 * drains the queue to disk. If the queue is full, the event is dropped and a
 * warning is logged rather than blocking the caller.</p>
 *
 * <p>If the log file grows beyond 50 MB, it is closed and a new file is opened
 * with an updated timestamp in the name.</p>
 */
@Component
public class RunLog {

    private static final Logger log = LoggerFactory.getLogger(RunLog.class);

    private static final int QUEUE_CAPACITY = 1024;
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Counters counters;
    private final String sessionTimestamp;

    private volatile boolean running;
    private Thread writerThread;
    private Path currentLogFile;

    /**
     * The current log file writer. After {@link #start()} completes, this field is
     * accessed exclusively from the writer thread (see {@link #writerLoop}) — including
     * the rollover open/close cycle. Safe without {@code volatile} due to that confinement.
     * Do NOT read or write from any other thread.
     */
    private BufferedWriter writer;

    public RunLog(Counters counters) {
        this(counters, null);
    }

    /**
     * Package-private constructor for testing — allows an explicit log directory
     * so tests don't write to the user's home directory.
     *
     * @param overrideDir if non-null, the log file is written here instead of the
     *                    default OS-specific path or the {@code MC_AGENT_RUN_LOG_DIR} env var.
     */
    RunLog(Counters counters, Path overrideDir) {
        this.counters = counters;
        this.sessionTimestamp = String.valueOf(System.currentTimeMillis());
        this.overrideDir = overrideDir;
    }

    private final Path overrideDir;

    @PostConstruct
    void start() {
        try {
            Path logDir = overrideDir != null ? overrideDir : resolveLogDirectory();
            Files.createDirectories(logDir);
            currentLogFile = logDir.resolve("run-" + sessionTimestamp + ".jsonl");
            openWriter(currentLogFile);
            log.info("RunLog writing to {}", currentLogFile);
        } catch (IOException e) {
            log.error("RunLog could not open log file — structured logging disabled", e);
            // Don't prevent startup; log() will be a no-op if writer stays null.
        }

        running = true;
        writerThread = new Thread(this::writerLoop, "mc-agent-runlog");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        writerThread.interrupt();
        // Drain remaining items synchronously so we don't lose the tail of the log.
        drainRemaining();
        closeWriter();
    }

    /**
     * Enqueue a structured event for asynchronous write. Non-blocking.
     * If the internal queue is full the event is dropped and a counter is incremented.
     *
     * @param eventType short camelCase or snake_case event name, e.g. {@code "llm_call"}
     * @param fields    additional key/value pairs to include in the JSON object
     */
    public void log(String eventType, Map<String, Object> fields) {
        if (!running || writer == null) {
            return;
        }
        String line = buildLine(eventType, fields);
        if (!queue.offer(line)) {
            counters.inc("runlog.dropped");
            LoggerFactory.getLogger(RunLog.class).warn("RunLog queue full — dropped event: {}", eventType);
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private String buildLine(String eventType, Map<String, Object> fields) {
        // Ordered so ts/cmd_id/event appear first in the output.
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        String cmdId = MDC.get(CorrelationId.MDC_KEY);
        if (cmdId != null && !cmdId.isBlank()) {
            obj.put("cmd_id", cmdId);
        }
        obj.put("event", eventType);
        if (fields != null) {
            obj.putAll(fields);
        }
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // Fallback: use Jackson to safely escape individual strings so values
            // containing quotes or backslashes cannot break the JSON structure.
            try {
                return mapper.writeValueAsString(
                        Map.of("ts", Instant.now().toString(), "event", eventType, "_fallback", true));
            } catch (JsonProcessingException ex) {
                // Absolute last resort — at this point Jackson itself is broken.
                return "{\"_fallback\":true,\"error\":\"serialization_failed\"}";
            }
        }
    }

    private void writerLoop() {
        int linesSinceFlush = 0;
        while (running || !queue.isEmpty()) {
            try {
                String line = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line == null) {
                    // Periodic flush even with no new events.
                    flushIfOpen();
                    linesSinceFlush = 0;
                    continue;
                }
                rolloverIfNeeded();
                writeLine(line);
                linesSinceFlush++;
                // Flush every 16 lines or once per drain cycle.
                if (linesSinceFlush >= 16) {
                    flushIfOpen();
                    linesSinceFlush = 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("RunLog writer error", e);
            }
        }
        flushIfOpen();
    }

    private void drainRemaining() {
        String line;
        while ((line = queue.poll()) != null) {
            try {
                rolloverIfNeeded();
                writeLine(line);
            } catch (Exception e) {
                // Best-effort during shutdown.
                LoggerFactory.getLogger(RunLog.class).warn("RunLog: failed to drain remaining event", e);
            }
        }
    }

    private void writeLine(String line) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            log.error("RunLog failed to write line", e);
        }
    }

    private void flushIfOpen() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            log.warn("RunLog flush failed", e);
        }
    }

    private void rolloverIfNeeded() {
        if (currentLogFile == null) {
            return;
        }
        try {
            if (Files.exists(currentLogFile) && Files.size(currentLogFile) > MAX_FILE_SIZE_BYTES) {
                closeWriter();
                String newName = "run-" + System.currentTimeMillis() + ".jsonl";
                currentLogFile = currentLogFile.getParent().resolve(newName);
                openWriter(currentLogFile);
                log.info("RunLog rolled over to {}", currentLogFile);
            }
        } catch (IOException e) {
            log.warn("RunLog rollover check failed", e);
        }
    }

    private void openWriter(Path path) throws IOException {
        writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.warn("RunLog writer close failed", e);
            }
            writer = null;
        }
    }

    private static Path resolveLogDirectory() {
        String envDir = System.getenv("MC_AGENT_RUN_LOG_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft", "logs", "mc-agent");
        }
        return Path.of(home, ".mc-agent", "logs");
    }

    /**
     * The path to the currently open log file. May be null if startup failed.
     * Package-private for tests.
     */
    Path currentLogFile() {
        return currentLogFile;
    }
}
