package com.mcagent.core;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.config.ChromaConfiguration;
import com.mcagent.core.config.InMemoryVectorStoreConfig;
import com.mcagent.core.config.LLMProperties;
import com.mcagent.core.config.LangChain4jConfig;
import com.mcagent.core.config.VectorStoreProperties;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.LocationRepository;
import com.mcagent.core.memory.PlayerNoteRepository;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.service.SafetyValidator;
import com.mcagent.core.tools.MinecraftTools;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Marker configuration that imports all core beans explicitly.
 * Avoids classpath scanning inside the mod environment for safety.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.mcagent.core.memory")
@Import({
        BotProperties.class,
        LLMProperties.class,
        VectorStoreProperties.class,
        LangChain4jConfig.class,
        ChromaConfiguration.class,
        InMemoryVectorStoreConfig.class,
        LangChain4jService.class,
        ChatService.class,
        SafetyValidator.class,
        MinecraftTools.class,
        LocationMemoryService.class,
        PlayerNoteService.class,
        VectorMemoryService.class
})
public class CoreApplication {
    // Intentionally empty — @Import defines the context
}
