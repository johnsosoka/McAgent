package com.mcagent.core.memory.episodic;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Holds the current session ID (server address) so services inside the core
 * module don't need to receive it as a method argument on every call.
 *
 * <p>Set once by {@code McAgentFabricMod.initSpringContext()} to the resolved
 * server address immediately after the Spring context starts. Falls back to
 * {@code "unknown"} if not explicitly initialized.</p>
 */
@Slf4j
@Component
public class SessionContext {

    private static final String UNKNOWN_SESSION = "unknown";

    @Getter
    private volatile String sessionId = UNKNOWN_SESSION;

    /**
     * Initialize the session ID. Should be called exactly once per Spring
     * context lifecycle, immediately after the context is refreshed.
     *
     * @param serverAddress the resolved server IP or {@code "single-player"}
     */
    public void setSessionId(String serverAddress) {
        this.sessionId = (serverAddress != null && !serverAddress.isBlank())
                ? serverAddress : UNKNOWN_SESSION;
        log.info("Session context set to: {}", this.sessionId);
    }
}
