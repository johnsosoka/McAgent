package com.mcagent.core.config;

import lombok.Data;
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

    @Data
    public static class ChatProperties {
        private int maxHistory = 20;
        private int responseTimeoutSeconds = 30;
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
}
