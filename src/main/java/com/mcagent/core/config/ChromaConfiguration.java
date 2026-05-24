package com.mcagent.core.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Spring configuration that creates a {@link ChromaEmbeddingStore} bean.
 * Properties are read from {@code vector-store.chroma} in application.yml.
 * Not active when the {@code dev} profile is enabled (in-memory store is used instead).
 */
@Configuration
@Profile("!dev")
public class ChromaConfiguration {

    @Bean
    public EmbeddingStore<TextSegment> chromaEmbeddingStore(VectorStoreProperties props) {
        VectorStoreProperties.ChromaProperties chroma = props.getChroma();
        String baseUrl = "http://" + chroma.getHost() + ":" + chroma.getPort();

        return ChromaEmbeddingStore.builder()
                .apiVersion(ChromaApiVersion.V2)
                .baseUrl(baseUrl)
                .collectionName(chroma.getCollection())
                .timeout(Duration.ofSeconds(chroma.getTimeoutSeconds()))
                .build();
    }
}
