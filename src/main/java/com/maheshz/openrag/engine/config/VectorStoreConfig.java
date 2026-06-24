package com.maheshz.openrag.engine.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return new PgVectorStore.Builder(jdbcTemplate, embeddingModel)
                .withDimensions(768)
                .withDistanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .withIndexType(PgVectorStore.PgIndexType.HNSW)
                .withInitializeSchema(true)
                .build();
    }
}