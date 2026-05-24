package com.mcagent.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LLMProperties {
    private String primaryProvider = "fireworks";
    private FireworksProperties fireworks = new FireworksProperties();
    private EmbeddingProperties embedding = new EmbeddingProperties();

    @Data
    public static class FireworksProperties {
        private String baseUrl = "https://api.fireworks.ai/inference/v1";
        private String apiKey = "";
        private String model = "accounts/fireworks/models/kimi-k2p5";
        private double temperature = 0.7;
        private int timeoutSeconds = 30;
        private int maxTokens = 2048;
    }

    @Data
    public static class EmbeddingProperties {
        private String provider = "fireworks";
        private String model = "nomic-ai/nomic-embed-text-v1.5";
    }
}
