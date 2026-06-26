package com.mcagent.core;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.config.ChromaConfiguration;
import com.mcagent.core.config.InMemoryVectorStoreConfig;
import com.mcagent.core.config.JpaConfig;
import com.mcagent.core.config.LLMProperties;
import com.mcagent.core.config.LangChain4jConfig;
import com.mcagent.core.config.VectorStoreProperties;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import com.mcagent.core.routine.AutoModeDriver;
import com.mcagent.core.routine.DefaultRoutineService;
import com.mcagent.core.routine.RoutineSeeder;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.service.SafetyValidator;
import com.mcagent.core.task.DefaultTaskExecutor;
import com.mcagent.core.task.JobSupervisor;
import com.mcagent.core.task.PendingApprovalStore;
import com.mcagent.core.task.PlanApprovalGate;
import com.mcagent.core.task.SessionResumer;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import com.mcagent.core.reflex.SurvivalReflexes;
import com.mcagent.core.telemetry.Watchdog;
import com.mcagent.core.tools.MinecraftTools;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Marker configuration that imports all core beans explicitly.
 * Avoids classpath scanning inside the mod environment for safety.
 */
@Configuration
@Import({
        JpaConfig.class,
        BotProperties.class,
        LLMProperties.class,
        VectorStoreProperties.class,
        LangChain4jConfig.class,
        ChromaConfiguration.class,
        InMemoryVectorStoreConfig.class,
        LangChain4jService.class,
        ChatService.class,
        SafetyValidator.class,
        Counters.class,
        JobTracker.class,
        RunLog.class,
        Watchdog.class,
        MinecraftTools.class,
        SurvivalReflexes.class,
        LocationMemoryService.class,
        PlayerNoteService.class,
        VectorMemoryService.class,
        JobSupervisor.class,
        DefaultTaskExecutor.class,
        SessionResumer.class,
        PendingApprovalStore.class,
        PlanApprovalGate.class,
        DefaultRoutineService.class,
        RoutineSeeder.class,
        AutoModeDriver.class
})
public class CoreApplication {
    // Intentionally empty — @Import defines the context
}
