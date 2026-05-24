package com.mcagent.fabric;

import com.mcagent.core.service.BotOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mod-level Spring configuration that provides Minecraft-specific beans.
 * These beans bridge the Fabric/Baritone world to the pure-Java core.
 */
@Configuration
public class FabricModConfig {

    @Bean
    public BotOperations botOperations() {
        return new FabricBaritoneBridge();
    }
}
