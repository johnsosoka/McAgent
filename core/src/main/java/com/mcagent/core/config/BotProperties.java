package com.mcagent.core.config;

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
        private boolean enabled = true;
        private int scanIntervalTicks = 20;
        private int threatRadius = 32;
        private int passiveRadius = 16;
        private String mode = "passive";
        private String messageMode = "individual";
        private int debounceSeconds = 10;
        private boolean trackPassiveMobs = true;
        private List<String> passiveMobTypes = List.of("Pig", "Cow", "Chicken", "Sheep");
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
