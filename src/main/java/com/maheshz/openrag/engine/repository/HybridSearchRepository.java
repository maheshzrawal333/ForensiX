package com.maheshz.openrag.engine.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class HybridSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper; // SENIOR FIX: Needed to parse JSONB metadata

    public HybridSearchRepository(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Document> performHybridSearch(String query, String tenantId, List<String> folderIds, int topK) {

        // 1. Create embedding and convert to Postgres vector string format
        float[] embeddingArray = embeddingModel.embed(query);
        String vectorString = Arrays.toString(embeddingArray);

        // 2. Handle Root Context (If list is empty, pass a null array so SQL searches everything)
        String[] folderIdArray = (folderIds == null || folderIds.isEmpty()) ? null : folderIds.toArray(new String[0]);

        // 3. Execute the hybrid search call
        String sql = "SELECT content, metadata FROM hybrid_search(?, ?::vector, ?, ?::text[], ?)";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata");

            // SENIOR FIX: Correctly parse the JSONB metadata from Postgres into a Java Map
            Map<String, Object> metadata = new HashMap<>();
            try {
                if (metadataJson != null && !metadataJson.isBlank()) {
                    metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                }
            } catch (Exception e) {
                log.warn("Failed to parse metadata JSON for document chunk.", e);
            }

            // Return the document WITH the metadata attached so the AI can cite it!
            return new Document(content, metadata);

        }, query, vectorString, tenantId, folderIdArray, topK);
    }
}