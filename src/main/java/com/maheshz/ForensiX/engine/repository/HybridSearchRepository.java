package com.maheshz.ForensiX.engine.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enterprise Data Access Layer for High-Performance Hybrid Vector Search.
 * <p>
 * ARCHITECTURAL DESIGN:
 * This class explicitly bypasses Hibernate/JPA in favor of raw Spring {@link JdbcTemplate}.
 * Standard ORMs are notoriously inefficient at handling high-dimensional vector math and
 * complex JSONB operations.
 * <p>
 * By using JDBC, we push the heavy lifting (Cosine/L2 distance calculations and BM25 keyword matching)
 * directly into a custom PostgreSQL stored function (`hybrid_search`). This minimizes network I/O
 * and drastically reduces JVM memory consumption, which is critical during massive RAG queries.
 */
@Repository
public class HybridSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    /**
     * Hyperparameter: Vector Similarity Cutoff.
     * <p>
     * RAG TUNING NOTE (Recall vs. Precision):
     * We explicitly relaxed this from 0.45 to 0.75. In forensic analysis, investigators often ask
     * questions that require deductive reasoning (e.g., "Was John hiding money?"). A strict threshold (0.45)
     * only returns exact semantic matches (e.g., text that explicitly says "John hid money"), dropping
     * vital circumstantial context.
     * <p>
     * Relaxing to 0.75 casts a wider net, pulling in tangential clues, and trusts the LLM's attention
     * mechanism to filter out the noise.
     */
    private static final double DISTANCE_THRESHOLD = 0.75;

    /**
     * Constructor injection ensures thread safety and immutability for the core data components.
     */
    public HybridSearchRepository(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes a hardware-accelerated hybrid search against the pgvector database.
     * <p>
     * SECURITY BOUNDARY: The {@code tenantId} is strictly passed directly into the SQL function.
     * This mathematically guarantees that the Postgres engine filters out vectors belonging to
     * other forensic cases BEFORE calculating similarity scores, completely preventing cross-tenant data leaks.
     *
     * @param query The investigator's raw natural language question.
     * @param tenantId The validated boundary ID for the active investigative case.
     * @param folderIds (Optional) An array of specific directories to narrow the search scope.
     * @param topK The maximum number of evidentiary chunks to retrieve (Token Window Management).
     * @return A list of Spring AI {@link Document} objects formatted for the LLM context window.
     */
    public List<Document> performHybridSearch(String query, String tenantId, List<String> folderIds, int topK) {
        log.debug("Executing vector search for tenant: {} | max_results: {}", tenantId, topK);

        // 1. Convert the natural language query into a high-dimensional mathematical vector.
        float[] embeddingArray = embeddingModel.embed(query);
        String vectorString = Arrays.toString(embeddingArray);

        // 2. Format the Java List into a raw string array compatible with Postgres TEXT[]
        String[] folderIdArray = (folderIds == null || folderIds.isEmpty()) ? null : folderIds.toArray(new String[0]);

        // 3. Execute the custom PostgreSQL stored function.
        // Explicit casting (e.g., ?::vector, ?::text[]) is required here because JDBC
        // does not natively understand pgvector types or advanced Postgres arrays.
        String sql = "SELECT content, metadata FROM hybrid_search(?::text, ?::vector, ?::text, ?::text[], ?::integer, ?::float)";

        List<Document> retrievedDocs = jdbcTemplate.query(
                sql,
                documentRowMapper(),
                query, vectorString, tenantId, folderIdArray, topK, DISTANCE_THRESHOLD
        );

        log.info("Hybrid search retrieved {} context chunks (filtered by relevance < {}).", retrievedDocs.size(), DISTANCE_THRESHOLD);
        return retrievedDocs;
    }

    /**
     * Maps the raw SQL ResultSet rows into Spring AI Document entities.
     *
     * @return A RowMapper configured to safely extract the text and parse the JSONB metadata.
     */
    private RowMapper<Document> documentRowMapper() {
        return (rs, rowNum) -> {
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata");

            Map<String, Object> metadata = new HashMap<>();

            // ROBUSTNESS PATTERN: Graceful Degradation on JSON parsing.
            // If the database contains a corrupted metadata JSON string, we catch the exception
            // and log it, rather than throwing a RuntimeException. Throwing would abort the
            // entire RowMapper iteration, dropping 99 valid documents just because 1 was corrupted.
            if (metadataJson != null && !metadataJson.isBlank()) {
                try {
                    metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.error("Failed to parse metadata JSON for document chunk.", e);
                }
            }

            return new Document(content, metadata);
        };
    }
}