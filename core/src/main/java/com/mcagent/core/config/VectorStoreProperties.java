package com.mcagent.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the vector store backend (ChromaDB or in-memory).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vector-store")
public class VectorStoreProperties {

    private String type = "chroma";
    private ChromaProperties chroma = new ChromaProperties();
    private InMemoryProperties inMemory = new InMemoryProperties();

    @Data
    public static class ChromaProperties {
        private String host = "localhost";
        private int port = 8000;
        private String collection = "mc_agent_locations";
        private int timeoutSeconds = 5;
    }

    @Data
    public static class InMemoryProperties {
        private String persistPath;
    }
}
