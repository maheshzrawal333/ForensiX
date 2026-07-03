package com.maheshz.ForensiX.engine.repository;

import com.maheshz.ForensiX.engine.domain.TenantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Enterprise Data Access Layer for Case-Specific AI Guardrails.
 * <p>
 * This repository manages the persistence of the {@link TenantConfig} entity.
 * In the ForensiX RAG pipeline, this is a critical infrastructure component.
 * Before any semantic search results are sent to the local LLM, the service layer
 * MUST query this repository to retrieve the specific model weights, temperature constraints,
 * and system prompts designated for the active investigative case.
 * <p>
 * ARCHITECTURAL DESIGN (Why is this empty?):
 * Because the {@code TenantConfig} shares a strict 1:1 Primary Key relationship with
 * the {@code Tenant} entity (using `tenantId` as its ID), we do not need to write custom
 * isolation queries (like {@code findByTenantId}).
 * <p>
 * The standard {@code findById(tenantId)} method provided by {@link JpaRepository}
 * inherently acts as both the lookup mechanism and the multi-tenant security perimeter,
 * offloading boilerplate CRUD operations entirely to the Hibernate proxy layer.
 */
@Repository
public interface TenantConfigRepository extends JpaRepository<TenantConfig, String> {

    // No custom JPQL or native queries are required here.
    // The base JpaRepository provides highly optimized, L1-cached lookups via findById().
}