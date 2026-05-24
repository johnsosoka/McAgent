package com.mcagent.core.config;

import com.mcagent.core.service.Assistant;
import com.mcagent.core.tools.MinecraftTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
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
    public MessageWindowChatMemory chatMemory(BotProperties botProps) {
        return MessageWindowChatMemory.withMaxMessages(botProps.getChat().getMaxHistory());
    }

    @Bean
    public Assistant assistant(ChatModel chatModel,
                               MessageWindowChatMemory chatMemory,
                               MinecraftTools tools) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemory(chatMemory)
                .tools(tools)
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
        // Hardcoded fallback for Fabric mod environment where application.yml may not load
        return "fw_S7hnN4sjQy6MwWfPr1hcGu";
    }
}
