package com.mcagent.core.config;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loads environment variables from .env files located in multiple candidate paths.
 *
 * <p>Checks locations in priority order:</p>
 * <ol>
 *   <li>Environment variable (already set)</li>
 *   <li>Java system property (e.g. {@code -DFIREWORKS_API_KEY=...})</li>
 *   <li>User-provided explicit path (passed from fabric-mod)</li>
 *   <li>Project working directory (for dev / Gradle runs)</li>
 *   <li>Standard Minecraft config directories (platform-specific)</li>
 * </ol>
 *
 * <p>Loaded values are injected into {@link System#setProperty(String, String)}
 * so Spring Boot's {@code ${...}} placeholders pick them up automatically.</p>
 */
@Slf4j
public final class EnvLoader {

    private EnvLoader() {
        // utility class
    }

    /**
     * Load .env variables from the best available source and expose them as system properties.
     *
     * @param explicitPath optional explicit file path (from fabric-mod layer). May be {@code null}.
     */
    public static void load(Path explicitPath) {
        List<Path> candidates = buildCandidatePaths(explicitPath);

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                log.info("Loading .env from {}", candidate);
                try {
                    Dotenv dotenv = Dotenv.configure()
                            .directory(candidate.getParent().toString())
                            .filename(candidate.getFileName().toString())
                            .ignoreIfMalformed()
                            .ignoreIfMissing()
                            .load();

                    dotenv.entries().forEach(e -> {
                        String key = e.getKey();
                        String value = e.getValue();
                        // Only set if not already present as env var or system property
                        if (System.getenv(key) == null && System.getProperty(key) == null) {
                            System.setProperty(key, value);
                            log.debug("Set property {} from .env", key);
                        }
                    });

                    log.info("Loaded {} variables from .env", dotenv.entries().size());
                    return;
                } catch (Exception e) {
                    log.warn("Failed to parse .env at {}: {}", candidate, e.getMessage());
                }
            }
        }

        log.info("No .env file found in any candidate path. Relying on environment variables / system properties.");
    }

    private static List<Path> buildCandidatePaths(Path explicitPath) {
        String home = System.getProperty("user.home");

        // Order matters — first match wins
        return List.of(
                explicitPath,                                         // fabric-mod may pass this
                Paths.get(".env"),                                    // project working directory (dev)
                Paths.get(home, "Library/Application Support/minecraft/config/mc-agent.env"), // macOS production
                Paths.get(home, ".minecraft/config/mc-agent.env"),    // Linux production
                Paths.get(home, "AppData/Roaming/.minecraft/config/mc-agent.env") // Windows production
        );
    }
}
