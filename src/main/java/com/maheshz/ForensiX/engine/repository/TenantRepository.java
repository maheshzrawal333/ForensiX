package com.maheshz.ForensiX.engine.repository;

import com.maheshz.ForensiX.engine.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Enterprise Data Access Layer for Global Case Management (Root Aggregate).
 * <p>
 * In the ForensiX architecture, the {@link Tenant} entity represents an entirely isolated
 * Investigative Case. This repository serves as the definitive global registry for case
 * existence, administrative provisioning, and root metadata.
 * <p>
 * ARCHITECTURAL EXCEPTION (The Root of Trust):
 * Unlike every other repository in this system (e.g., DocumentRepository, FolderRepository)
 * which mandate a `tenantId` parameter to enforce row-level security, this repository
 * operates at the global/administrative tier.
 * <p>
 * It is actively queried during the HTTP request lifecycle (often by security filters or
 * interceptors) to validate the existence of the incoming `X-Tenant-ID` header *before* * downstream, tenant-scoped operations are permitted to execute.
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    // -----------------------------------------------------------
    // FRAMEWORK DELEGATION
    // -----------------------------------------------------------
    // No custom JPQL or native queries are required here.
    // By extending JpaRepository, Spring Proxy magically generates highly optimized,
    // L1-cached, and transaction-aware implementations for our core administrative workflows:
    //
    // 1. findById(String id): The critical lookup method used to validate if a requested
    //    case ID actually exists before allocating resources or establishing the context holder.
    // 2. findAll(): Utilized by the UI Bootstrap process to populate case-selection menus.
    // 3. save(Tenant t): Utilized by administrative boundaries to provision new case boundaries.
}