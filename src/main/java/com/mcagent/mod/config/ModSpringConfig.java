package com.mcagent.mod.config;

import com.mcagent.core.service.BotOperations;
import com.mcagent.mod.service.BaritoneOperationsImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mod-level Spring configuration that provides Minecraft-specific beans.
 * These beans bridge the Forge/Baritone world to the pure-Java core.
 */
@Configuration
public class ModSpringConfig {

    @Bean
    public BotOperations botOperations() {
        return new BaritoneOperationsImpl();
    }
}
