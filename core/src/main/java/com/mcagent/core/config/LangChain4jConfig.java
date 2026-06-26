package com.mcagent.core.config;

import com.mcagent.core.memory.chat.LayeredChatMemory;
import com.mcagent.core.memory.chat.RollingSummaryService;
import com.mcagent.core.service.Assistant;
import com.mcagent.core.service.PlanFailureExplainer;
import com.mcagent.core.service.Planner;
import com.mcagent.core.service.ReflectionService;
import com.mcagent.core.tools.MinecraftTools;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures LangChain4j components: chat model, memory, and AI service.
 */
@Configuration
public class LangChain4jConfig {

    @Bean
    public ChatModel chatModel(LLMProperties props) {
        LLMProperties.FireworksProperties fw = props.getFireworks();
        return OpenAiChatModel.builder()
                .baseUrl(fw.getBaseUrl())
                .apiKey(resolveApiKey(fw.getApiKey()))
                .modelName(fw.getModel())
                .temperature(fw.getTemperature())
                .timeout(Duration.ofSeconds(fw.getTimeoutSeconds()))
                .maxTokens(fw.getMaxTokens())
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(LLMProperties props) {
        LLMProperties.FireworksProperties fw = props.getFireworks();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(fw.getBaseUrl())
                .apiKey(resolveApiKey(fw.getApiKey()))
                .modelName(props.getEmbedding().getModel())
                .timeout(Duration.ofSeconds(fw.getTimeoutSeconds()))
                .build();
    }

    @Bean
    public RollingSummaryService rollingSummaryService(ChatModel chatModel, BotProperties botProps) {
        return new RollingSummaryService(chatModel, botProps.getChat().getSummaryMaxChars());
    }

    @Bean
    public LayeredChatMemory chatMemory(BotProperties botProps, RollingSummaryService rollingSummaryService) {
        BotProperties.ChatProperties chat = botProps.getChat();
        return new LayeredChatMemory(chat.getEffectiveWorkingWindow(), chat.getSummaryTrigger(), rollingSummaryService);
    }

    @Bean
    public Assistant assistant(ChatModel chatModel,
                               ChatMemory chatMemory,
                               MinecraftTools tools) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemory(chatMemory)
                .tools(tools)
                .build();
    }

    /**
     * Stateless planner role (GITM-style Decomposer).
     *
     * <p>No memory, no tools — the planner's only job is to turn a natural-language
     * player request into a structured {@link com.mcagent.core.service.PlanDraft}.
     * Sprint 14 P6 will route this to a dedicated cheaper model endpoint; for
     * Sprint 13 v1 it shares the same {@code ChatModel} as the {@link Assistant}.</p>
     */
    @Bean
    public Planner planner(ChatModel chatModel) {
        return AiServices.builder(Planner.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * Stateless failure-critic role (DEPS-style Explain step).
     *
     * <p>No memory, no tools — given a structured failure context, the explainer
     * returns a {@link com.mcagent.core.service.FailureDecision} (RETRY / REVISE /
     * ABANDON) with a 1–2 sentence natural-language explanation. Sprint 14 P6 will
     * route this to a dedicated critic model; for Sprint 14 v1 it shares the same
     * {@code ChatModel} as the {@link Assistant}.</p>
     */
    @Bean
    public PlanFailureExplainer planFailureExplainer(ChatModel chatModel) {
        return AiServices.builder(PlanFailureExplainer.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * Stateless reflection role (Reflexion-pattern postmortem writer).
     *
     * <p>No memory, no tools — given a task context string, this service produces
     * a 2-3 sentence plain-text postmortem. Persisted as an episodic memory event
     * so future planning turns can learn from past outcomes.</p>
     *
     * <p>Called by {@link com.mcagent.core.task.JobSupervisor} after a plan reaches
     * a terminal state (COMPLETED or FAILED). CANCELLED plans are skipped.</p>
     */
    @Bean
    public ReflectionService reflectionService(ChatModel chatModel) {
        return AiServices.builder(ReflectionService.class)
                .chatModel(chatModel)
                .build();
    }

    private static String resolveApiKey(String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            return configuredKey;
        }
        String envKey = System.getenv("FIREWORKS_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }
        String propKey = System.getProperty("FIREWORKS_API_KEY");
        if (propKey != null && !propKey.isBlank()) {
            return propKey;
        }
        throw new IllegalStateException(
                "Fireworks API key not configured. Set 'llm.fireworks.api-key' in application.yml, " +
                        "the FIREWORKS_API_KEY environment variable, or add it to .env / mc-agent.env."
        );
    }
}
