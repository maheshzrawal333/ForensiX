package com.maheshz.ForensiX.engine.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Enterprise Vector Garbage Collection and Schema Isolation Boundary.
 * <p>
 * In a production RAG architecture, when an investigator deletes a physical file (e.g., from S3)
 * or a relational record (e.g., from the DocumentRepository), the corresponding high-dimensional
 * semantic chunks must also be aggressively purged from the vector database. Failure to do so
 * results in "Ghost Vectors"—where the LLM cites evidence that legally no longer exists in the case.
 * <p>
 * ARCHITECTURAL DESIGN:
 * Spring AI's generic `VectorStore` interface is often too high-level to perform bulk,
 * multi-conditional deletes against custom metadata fields efficiently.
 * This repository deliberately encapsulates raw PostgreSQL-specific JSONB syntax (`->>`).
 * By isolating this dialect-specific SQL here, the core EvidenceService remains completely
 * database-agnostic and free of leaky persistence abstractions.
 */
@Repository
public class VectorCleanupRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection ensures thread safety and immutability for the JDBC template.
     */
    public VectorCleanupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Irreversibly obliterates all vector embeddings associated with a specific document.
     * <p>
     * PERFORMANCE NOTE (Push-Down Operation):
     * A 500-page PDF might be chunked into 2,000 separate vector rows. Instead of retrieving
     * those 2,000 IDs into JVM memory to delete them one-by-one via an ORM, this method pushes
     * a single, highly optimized bulk DELETE command directly down to the Postgres engine.
     * <p>
     * SECURITY BOUNDARY (Double Verification):
     * We do not rely solely on the `documentId`. By explicitly enforcing the `tenantId` match
     * inside the JSONB payload (`metadata->>'tenant_id' = ?`), we mathematically prevent an
     * Insecure Direct Object Reference (IDOR) attack. If a rogue API request attempts to pass a
     * document ID belonging to another case, this query will cleanly affect 0 rows.
     *
     * @param documentId The UUID of the original uploaded file.
     * @param tenantId The validated boundary ID for the active investigative case.
     * @return The exact number of vector chunk rows successfully deleted (useful for telemetry and audit logs).
     */
    public int wipeVectorsByDocumentId(String documentId, String tenantId) {

        // Utilizes PostgreSQL's native JSONB extraction operator (->>) to filter rows
        // dynamically based on the arbitrary metadata injected during the ingestion phase.
        String sql = "DELETE FROM vector_store WHERE metadata->>'document_id' = ? AND metadata->>'tenant_id' = ?";

        return jdbcTemplate.update(sql, documentId, tenantId);
    }
}