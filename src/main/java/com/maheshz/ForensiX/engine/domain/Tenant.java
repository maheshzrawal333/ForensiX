package com.maheshz.ForensiX.engine.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/**
 * Enterprise Root Aggregate for Multi-Tenant Isolation.
 * <p>
 * In the ForensiX architecture, a "Tenant" is strictly synonymous with an "Investigative Case".
 * This entity serves as the absolute root of the data hierarchy. Every other domain entity
 * (Folders, Documents, Vectors) extends {@code BaseTenantEntity} to point back to this specific record.
 * <p>
 * ARCHITECTURAL NOTE:
 * Notice that this class does NOT extend {@code BaseTenantEntity}. It cannot contain a `tenant_id`
 * column because its own Primary Key (`id`) is the value that all other tables use for foreign
 * key isolation.
 */
@Entity
public class Tenant {

    /**
     * The primary cryptographic and logical boundary key.
     * <p>
     * SECURITY IMPLICATION: This exact string is what the frontend UI passes via the
     * `X-Tenant-ID` HTTP header, which the `TenantInterceptor` validates.
     * Notice there is no `@GeneratedValue` here—Case IDs are typically externally defined
     * or intentionally structured (e.g., "CASE-2026-04A") rather than arbitrary UUIDs.
     */
    @Id
    private String id;

    /**
     * The human-readable classification or codename for the investigation.
     * Used exclusively for UI presentation and reporting.
     */
    private String name;

    /**
     * Immutable Audit Timestamp.
     * Establishes the exact moment the investigative perimeter was legally instantiated in the system.
     */
    private LocalDateTime createdAt;

    /**
     * No-Argument Constructor.
     * <p>
     * REQUIRED BY HIBERNATE: JPA specifications mandate a public or protected no-arg constructor
     * so the ORM can instantiate proxy objects via reflection during database hydration.
     * Do not use this constructor for business logic.
     */
    public Tenant() {}

    /**
     * Business Logic Constructor.
     * <p>
     * Ensures that a Case cannot be instantiated without its critical identifiers.
     * Automatically stamps the exact server time for the audit log, preventing the
     * client from spoofing creation dates.
     *
     * @param id The immutable, globally unique case identifier.
     * @param name The display name of the case.
     */
    public Tenant(String id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = LocalDateTime.now();
    }

    // ==========================================
    // GETTERS & SETTERS
    // ==========================================
    // Standard mutators required by the Hibernate ORM.

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}