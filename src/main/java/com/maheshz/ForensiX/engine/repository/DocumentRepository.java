package com.maheshz.ForensiX.engine.repository;

import com.maheshz.ForensiX.engine.domain.JobStatus;
import com.maheshz.ForensiX.engine.domain.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Enterprise Data Access Layer for Forensic Evidence Metadata.
 * <p>
 * This repository serves as the primary relational persistence boundary for the ForensiX engine.
 * While raw files live in S3 and semantic embeddings live in pgvector, this repository
 * tracks the definitive state of the evidence, managing its location, async ingestion status,
 * and multi-tenant security perimeter.
 * <p>
 * ARCHITECTURAL RULE: With the exception of background worker lookups (e.g., by Job ID),
 * EVERY query in this interface MUST enforce the {@code tenantId} parameter to mathematically
 * guarantee that no investigator can ever query or modify evidence belonging to another case.
 */
@Repository
public interface DocumentRepository extends JpaRepository<KnowledgeDocument, String> {

    /**
     * Retrieves a document based on its asynchronous worker correlation ID.
     * <p>
     * ASYNC PIPELINE USE: This is utilized primarily by the background AI workers (which operate
     * outside the standard HTTP request lifecycle) to locate the metadata record associated with
     * their current extraction or vectorization task.
     *
     * @param jobId The globally unique correlation ID generated at upload.
     * @return An Optional containing the document if found.
     */
    Optional<KnowledgeDocument> findByJobId(String jobId);

    /**
     * Retrieves all evidence scoped strictly to a specific folder and case.
     * <p>
     * SECURITY BOUNDARY: This is the primary query powering the frontend UI. By forcing the
     * inclusion of {@code tenantId}, we prevent Insecure Direct Object Reference (IDOR) attacks
     * where a malicious user attempts to enumerate folder IDs belonging to other investigations.
     *
     * @param folderId The target directory UUID.
     * @param tenantId The validated investigative case ID.
     * @return A list of documents isolated to the provided boundaries.
     */
    List<KnowledgeDocument> findByFolderIdAndTenantId(String folderId, String tenantId);

    /**
     * Safely retrieves a single document for modification or deletion.
     * <p>
     * Rather than blindly trusting {@code findById}, this method enforces tenant ownership.
     * If an API request attempts to delete document "A" while authenticated as case "B",
     * this will cleanly return empty, resulting in a standard 404 rather than a data leak.
     *
     * @param id The primary key of the document.
     * @param tenantId The validated investigative case ID.
     * @return An Optional containing the document if it exists AND belongs to the tenant.
     */
    Optional<KnowledgeDocument> findByIdAndTenantId(String id, String tenantId);

    /**
     * Validation check to prevent exact-name duplication within a single directory.
     * <p>
     * Used during the pre-flight checks of the ingestion controller to ensure investigators
     * do not accidentally upload the exact same evidence file (e.g., "bank_statement.pdf")
     * into the same folder twice, which would result in duplicated vectors and hallucinated RAG weights.
     *
     * @param fileName The human-readable name of the file.
     * @param folderId The directory it is being uploaded to.
     * @param tenantId The active case boundary.
     * @return True if a collision is detected, false otherwise.
     */
    boolean existsByFileNameAndFolderIdAndTenantId(String fileName, String folderId, String tenantId);

    /**
     * Performs a high-performance, atomic status update for the background processing pipeline.
     * <p>
     * ARCHITECTURAL OPTIMIZATION:
     * In a high-throughput ingestion scenario, doing a standard {@code findById()} followed
     * by a {@code setStatus()} and {@code save()} requires an expensive SELECT, loads the full
     * entity into the Hibernate L1 cache, and risks OptimisticLocking exceptions if multiple
     * threads interact with the file simultaneously.
     * <p>
     * By using {@code @Modifying} and a direct JPQL {@code @Query}, we execute a single,
     * lightweight SQL UPDATE statement directly against the database, bypassing the persistence
     * context entirely and maximizing worker throughput.
     *
     * @param id The primary key of the document being processed.
     * @param status The new lifecycle state (e.g., PROCESSING, COMPLETED, FAILED).
     */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeDocument d SET d.status = :status WHERE d.id = :id")
    void updateJobStatus(@Param("id") String id, @Param("status") JobStatus status);
}