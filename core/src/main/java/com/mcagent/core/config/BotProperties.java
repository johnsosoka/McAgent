package com.mcagent.core.config;

import com.mcagent.core.observer.BotMode;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "bot")
public class BotProperties {
    private String name = "McAgent";
    private List<String> triggerWords = List.of("bot", "agent");
    private ChatProperties chat = new ChatProperties();
    private SafetyProperties safety = new SafetyProperties();
    private ObservationProperties observation = new ObservationProperties();
    private ReflexProperties reflex = new ReflexProperties();
    private JobProperties job = new JobProperties();
    private PlanProperties plan = new PlanProperties();
    private RoutineProperties routine = new RoutineProperties();

    @Data
    public static class ChatProperties {

        private static final Logger log = LoggerFactory.getLogger(ChatProperties.class);

        static final int DEFAULT_WORKING_WINDOW = 12;
        static final int DEFAULT_MAX_HISTORY = 20;

        /**
         * Maximum number of messages kept in the active working window.
         * Messages beyond this limit are evicted into the pending-summary buffer.
         */
        private int workingWindow = DEFAULT_WORKING_WINDOW;

        /**
         * Number of evicted messages that must accumulate before a rolling summary
         * call is triggered. Tune this to balance LLM call frequency vs. context depth.
         */
        private int summaryTrigger = 8;

        /**
         * @deprecated Use {@code workingWindow} instead. Kept for backwards compatibility
         *             with existing {@code application.yml} files that set {@code max-history}.
         *             When only {@code max-history} is set, its value is honoured and a
         *             WARN is emitted at startup.
         */
        @Deprecated
        private int maxHistory = DEFAULT_MAX_HISTORY;

        /**
         * Maximum number of characters retained from a prior rolling summary before
         * it is truncated. Prevents unbounded growth across many summarization cycles.
         */
        private int summaryMaxChars = 800;

        private int responseTimeoutSeconds = 30;

        /**
         * Minimum interval between outbound chat messages in milliseconds.
         * Mojang's signed-chat enforcement floor is 1000ms; 1200ms is a safe margin above it.
         * Applied to {@link com.mcagent.fabric.FabricChatSender#MIN_INTERVAL_MS} at startup.
         */
        private long minIntervalMs = 1200;

        /**
         * Returns the effective working-window size, honouring the deprecated
         * {@code max-history} property for backwards compatibility.
         *
         * <ul>
         *   <li>If {@code workingWindow} has been overridden (differs from default),
         *       return {@code workingWindow}.</li>
         *   <li>Else if {@code maxHistory} has been overridden (differs from default),
         *       log a single WARN and return {@code maxHistory}.</li>
         *   <li>Otherwise return the default {@code workingWindow}.</li>
         * </ul>
         *
         * @return the effective window size to use when constructing {@link com.mcagent.core.memory.chat.LayeredChatMemory}
         */
        public int getEffectiveWorkingWindow() {
            if (workingWindow != DEFAULT_WORKING_WINDOW) {
                return workingWindow;
            }
            if (maxHistory != DEFAULT_MAX_HISTORY) {
                log.warn("bot.chat.max-history={} is DEPRECATED; rename to bot.chat.working-window. "
                        + "Honoring the deprecated value for backwards compatibility.", maxHistory);
                return maxHistory;
            }
            return workingWindow;
        }
    }

    @Data
    public static class SafetyProperties {
        private boolean requireConfirmationForDangerous = true;
        private List<String> dangerousBlocks = List.of("TNT", "LAVA");
        private int maxMiningDepth = 16;
    }

    @Data
    public static class ObservationProperties {

        private static final Logger obsLog = LoggerFactory.getLogger(ObservationProperties.class);

        /**
         * Current operating mode. Defaults to {@link BotMode#WATCH} so the observer is
         * on but passive until the player explicitly enables autonomous action.
         */
        private BotMode mode = BotMode.WATCH;

        private int scanIntervalTicks = 20;
        private int threatRadius = 32;
        private int passiveRadius = 16;
        private String messageMode = "individual";
        private int debounceSeconds = 10;
        private boolean trackPassiveMobs = true;
        private List<String> passiveMobTypes = List.of("Pig", "Cow", "Chicken", "Sheep");

        /**
         * Maximum threat distance (blocks) to publish when a low-risk task is RUNNING.
         * Threats beyond this radius are suppressed so the LLM is not interrupted during
         * routine long-running operations such as mining. Threats within the radius are
         * always published regardless of task state.
         *
         * <p>Default: 8 blocks (approximately one combat range).</p>
         */
        private int lowRiskThreatRadius = 8;

        // ------------------------------------------------------------------
        // Backward-compatibility shims for legacy callers
        // ------------------------------------------------------------------

        /**
         * Derives enabled state from the current mode.
         * {@link BotMode#STANDBY} is the only mode where observation is off.
         *
         * @deprecated Query {@link #getMode()} instead.
         */
        @Deprecated
        public boolean isEnabled() {
            return mode != BotMode.STANDBY;
        }

        /**
         * Maps a boolean enabled flag back to a {@link BotMode}.
         * {@code false} → {@link BotMode#STANDBY}; {@code true} → {@link BotMode#WATCH}
         * (preserves existing passive-on semantics when re-enabling).
         *
         * @deprecated Use {@link #setMode(BotMode)} instead.
         */
        @Deprecated
        public void setEnabled(boolean enabled) {
            obsLog.debug("Deprecated setEnabled({}) called; mapping to BotMode. Use setMode() instead.", enabled);
            if (!enabled) {
                this.mode = BotMode.STANDBY;
            } else if (this.mode == BotMode.STANDBY) {
                this.mode = BotMode.WATCH;
            }
            // If already WATCH or AUTO, keep the current mode — enabling should not downgrade AUTO → WATCH.
        }

        /**
         * Returns the mode as a legacy string value for callers that have not yet migrated.
         * Maps: {@link BotMode#AUTO} → {@code "active"}, {@link BotMode#WATCH} → {@code "passive"},
         * {@link BotMode#STANDBY} → {@code "off"}.
         *
         * @deprecated Use {@link #getMode()} returning {@link BotMode}.
         */
        @Deprecated
        public String getModeString() {
            return switch (mode) {
                case AUTO -> "active";
                case WATCH -> "passive";
                case STANDBY -> "off";
            };
        }

        /**
         * Sets the mode from a legacy string value. Accepted values (case-insensitive):
         * {@code "active"}, {@code "passive"}, {@code "off"}.
         *
         * @deprecated Use {@link #setMode(BotMode)} instead.
         * @throws IllegalArgumentException if the string does not map to a known mode.
         */
        @Deprecated
        public void setModeFromString(String legacyMode) {
            obsLog.debug("Deprecated setModeFromString('{}') called. Use setMode(BotMode) instead.", legacyMode);
            BotMode mapped = switch (legacyMode.toLowerCase()) {
                case "active" -> BotMode.AUTO;
                case "passive" -> BotMode.WATCH;
                case "off" -> BotMode.STANDBY;
                default -> throw new IllegalArgumentException(
                        "Unknown legacy mode string '" + legacyMode + "'. Expected 'active', 'passive', or 'off'.");
            };
            this.mode = mapped;
        }
    }

    /**
     * Configuration for the heartbeat emitted when a Plan is RUNNING.
     *
     * <p>Heartbeats provide brief structured progress updates via outbound chat so the
     * player can track long-running tasks without issuing a {@code status} command.</p>
     */
    @Data
    public static class JobProperties {

        /**
         * Seconds a plan must be RUNNING before the first heartbeat is emitted.
         * Default: 60s — suppresses noise for short tasks that complete quickly.
         */
        private int heartbeatGraceSeconds = 60;

        /**
         * Minimum seconds between consecutive heartbeat messages.
         * Default: 60s — at most one heartbeat per minute.
         */
        private int heartbeatCooldownSeconds = 60;

        /**
         * Suppress heartbeats when the player sent a message within this many seconds.
         * Default: 30s — avoids interrupting an active conversation.
         */
        private int suppressIfRecentChatSeconds = 30;
    }

    /**
     * Configuration for the pre-execution approval gate ({@link com.mcagent.core.task.PlanApprovalGate}).
     *
     * <p>Plans exceeding any of these thresholds are held as
     * {@link com.mcagent.core.task.TaskState#PENDING_APPROVAL} until the player
     * confirms with {@code agent go} or {@code agent approved}.</p>
     */
    @Data
    public static class PlanProperties {

        /**
         * Plans with more than this many steps are flagged for approval.
         * Default: 3 steps.
         */
        private int approvalStepThreshold = 3;

        /**
         * Plans whose total estimated wall-clock duration exceeds this value
         * (in seconds) are flagged for approval.
         * Default: 300s (5 minutes).
         */
        private int approvalDurationSeconds = 300;

        /**
         * Maximum number of LLM-driven failure escalations per Plan before the
         * supervisor forces an ABANDON without making another LLM call.
         *
         * <p>Guards against runaway escalation loops where REVISE produces a plan
         * that continues to fail. The default of 2 matches the DEPS paper's
         * recommendation: explain twice, then give up rather than thrash.</p>
         */
        private int maxEscalationsPerPlan = 2;
    }

    /**
     * Configuration for the {@link com.mcagent.core.routine.AutoModeDriver} that selects
     * idle goals in {@link com.mcagent.core.observer.BotMode#AUTO} mode.
     */
    @Data
    public static class RoutineProperties {

        /** Master switch for the AutoModeDriver. When false, no autonomous routine selection occurs. */
        private boolean autoDriverEnabled = true;

        /** Default base X coordinate used by routines that navigate to the base (e.g. returnHome, nightShelter). */
        private int baseX = 0;

        /** Default base Y coordinate for base-navigation routines. */
        private int baseY = 64;

        /** Default base Z coordinate for base-navigation routines. */
        private int baseZ = 0;
    }

    /**
     * Configuration for the deterministic survival reflex layer ({@link com.mcagent.core.reflex.SurvivalReflexes}).
     *
     * <p>All thresholds use Minecraft's native scales:
     * health is 0–20, food level is 0–20, and light level is 0–15.</p>
     */
    @Data
    public static class ReflexProperties {

        /** Master switch. When false, {@code AutonomousObserver} skips the reflex tick entirely. */
        private boolean enabled = true;

        /**
         * Food level below which the bot attempts to eat (Rule 2).
         * Minecraft uses a 0–20 scale; 8 corresponds to one visible hunger bar depleted.
         */
        private int eatThreshold = 8;

        /**
         * HP percentage (0–100) below which the bot enters critical-safety mode (Rule 1).
         * Compared against {@code health / maxHealth * 100}.
         */
        private int healthCriticalPct = 30;

        /**
         * Distance in blocks the bot should flee from the closest threat (Rule 1).
         */
        private int fleeDistance = 16;

        /**
         * Combined light level below which the bot places a torch at night (Rule 3).
         * Minecraft's light scale is 0–15; 7 is the typical mob-spawning boundary.
         */
        private int darkLightThreshold = 7;
    }
}
