package com.maheshz.ForensiX.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Enterprise Domain Entity for Hierarchical Case Organization.
 * <p>
 * In the ForensiX RAG architecture, folders are not merely visual UI components;
 * they define strict boundary boxes for Vector Similarity Searches. By grouping evidence
 * into specific folders (e.g., "Financial Records", "Suspect Interrogations"), investigators
 * can filter the LLM's context window, significantly reducing hallucinations and token noise.
 * <p>
 * MULTI-TENANCY: By extending {@link BaseTenantEntity}, this table strictly inherits
 * the Case ID (`tenantId`) and creation audit timestamps, mathematically guaranteeing
 * that folder structures cannot leak across different forensic investigations.
 */
@Entity
public class Folder extends BaseTenantEntity {

    /**
     * The primary key for the directory.
     * <p>
     * ARCHITECTURAL NOTE: We use String (UUID v4) rather than auto-incrementing Longs
     * to prevent Insecure Direct Object Reference (IDOR) enumeration attacks, and to
     * allow the frontend UI to optimistically generate folder IDs before the network request completes.
     */
    @Id
    private String id;

    /**
     * Hierarchical Pointer (Adjacency List Pattern).
     * <p>
     * This field establishes the nested tree structure of the case files within a standard
     * relational database.
     * <p>
     * Note: {@code nullable = true} is explicitly required because top-level "Root" folders
     * will not possess a parent ID.
     */
    @Column(nullable = true)
    private String parentFolderId;

    /**
     * The human-readable display name of the directory.
     * Enforced at the schema level to prevent corrupt, nameless directories from breaking the UI.
     */
    @Column(nullable = false)
    private String name;

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

    public String getParentFolderId() {
        return parentFolderId;
    }

    public void setParentFolderId(String parentFolderId) {
        this.parentFolderId = parentFolderId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}