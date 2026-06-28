package com.maheshz.openrag.engine.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SENIOR FIX: Database Agnosticism.
 * Isolates Postgres-specific JSONB metadata queries away from the business service layer.
 */
@Repository
public class VectorCleanupRepository {

    private final JdbcTemplate jdbcTemplate;

    public VectorCleanupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int wipeVectorsByDocumentId(String documentId, String tenantId) {
        String sql = "DELETE FROM vector_store WHERE metadata->>'document_id' = ? AND metadata->>'tenant_id' = ?";
        return jdbcTemplate.update(sql, documentId, tenantId);
    }
}
