package com.maheshz.ForensiX.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;

/**
 * Enterprise Schema Contract for Multi-Tenant Data Governance.
 * <p>
 * This abstract class serves as the foundational database blueprint for all domain entities
 * in the ForensiX platform. By utilizing the JPA {@code @MappedSuperclass} pattern, we force
 * every subclass (e.g., KnowledgeDocument, Folder, Report) to inherit these columns at the
 * database level.
 * <p>
 * ARCHITECTURAL PURPOSE:
 * This prevents catastrophic human error. If a developer creates a new entity but forgets
 * to add a Tenant ID, that data becomes globally visible. By forcing inheritance from this
 * class, we guarantee row-level security and auditability across the entire PostgreSQL schema.
 */
@MappedSuperclass
public abstract class BaseTenantEntity {

    /**
     * The Cryptographic/Logical Boundary Key.
     * <p>
     * SECURITY CONSTRAINT: Notice {@code updatable = false}. Once a piece of evidence or
     * folder is assigned to a specific Case (Tenant), it can NEVER be transferred to another
     * case via a JPA update. This immutability prevents "tenant-hopping" bugs where a rogue
     * update accidentally reassigns evidence to the wrong investigation.
     */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    /**
     * Immutable Audit Timestamp.
     * <p>
     * CHAIN OF CUSTODY: In forensic software, knowing exactly when a record was instantiated
     * is a legal requirement. {@code updatable = false} ensures that once this timestamp is written,
     * no application logic can alter the creation date to manipulate the timeline.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ==========================================
    // GETTERS & SETTERS
    // ==========================================
    // Required by Hibernate for reflection and hydration during database queries.

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}