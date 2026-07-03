package com.maheshz.ForensiX.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;

/**
 * Enterprise Relational Anchor for Unstructured Forensic Evidence.
 * <p>
 * This entity does not store the physical file bytes (which reside in S3/MinIO) nor
 * the high-dimensional embeddings (which reside in pgvector). Instead, it acts as the
 * central metadata ledger.
 * <p>
 * It provides the vital link between the frontend UI (displaying the file name),
 * the async processing worker (tracking the job status), and the vector database
 * (scoping searches by folder and tenant boundaries).
 */
@Entity
public class KnowledgeDocument extends BaseTenantEntity {

    /**
     * The primary relational identifier.
     * <p>
     * ARCHITECTURAL NOTE: We use UUIDs rather than sequential Longs. This prevents
     * Insecure Direct Object Reference (IDOR) attacks, guarantees uniqueness across
     * distributed PostgreSQL nodes, and allows the backend to safely map this ID to
     * the corresponding S3 object key.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * The Vector Search Boundary parameter.
     * <p>
     * Note: We are deliberately using a loose relational mapping (`String folderId`)
     * instead of a strict JPA `@ManyToOne(targetEntity = Folder.class)` association.
     * In high-throughput ingestion pipelines, loose mapping prevents N+1 fetching issues
     * and avoids unnecessary database locks when deleting or moving millions of vector chunks.
     */
    @Column(nullable = false)
    private String folderId;

    /**
     * The original, human-readable name of the uploaded file (e.g., "bank_statements_2025.pdf").
     * Retained strictly for UI presentation and audit logging.
     */
    @Column(nullable = false)
    private String fileName;

    /**
     * The unique Correlation ID for the Asynchronous Ingestion Pipeline.
     * <p>
     * By enforcing a {@code unique = true} constraint at the database schema level,
     * we guarantee that an edge-case network retry from the frontend cannot accidentally
     * spawn duplicate background worker threads for the exact same upload task.
     */
    @Column(nullable = false, unique = true)
    private String jobId;

    /**
     * The finite state machine tracker for the ingestion worker.
     * <p>
     * Enforced as {@code EnumType.STRING} to ensure database readability. If this was left
     * as the default (ORDINAL), adding a new state to the enum later could shift the integer
     * values and silently corrupt the database.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    // ==========================================
    // GETTERS & SETTERS
    // ==========================================
    // Standard mutators required by the Hibernate ORM for entity hydration and reflection.

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }
}