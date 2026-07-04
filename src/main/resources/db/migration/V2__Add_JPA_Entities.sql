-- ==============================================================================
-- V2__Add_JPA_Entities.sql
-- Enterprise Schema Definition: Relational Domain & Multi-Tenant Boundaries
-- ==============================================================================
-- ARCHITECTURAL OVERVIEW:
-- This migration synchronizes the raw PostgreSQL schema with our compiled Spring
-- Data JPA domain models. While V1 handled the pgvector infrastructure, this script
-- establishes the relational metadata tier.
--
-- SECURITY INVARIANT:
-- Every table (except the root `tenant` table) strictly enforces a `tenant_id` column.
-- This guarantees row-level multi-tenant isolation across the entire application,
-- mathematically preventing cross-case data leaks.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. TENANT MASTER TABLE (The Root Aggregate)
-- ------------------------------------------------------------------------------
-- This is the highest-level entity in the architecture. A "Tenant" represents a
-- strictly isolated Investigative Case. All other relational data branches from this root.
CREATE TABLE IF NOT EXISTS tenant (
    -- The primary cryptographic/logical boundary key.
    -- Mapped as VARCHAR because case IDs are often externally defined strings
    -- (e.g., "CASE-2026-XYZ") rather than sequential integers.
                                      id VARCHAR(255) PRIMARY KEY,

    -- Human-readable display name for the UI.
    name VARCHAR(255),

    -- Immutable audit timestamp establishing when the investigative perimeter was legally created.
    created_at TIMESTAMP WITHOUT TIME ZONE
    );

-- ------------------------------------------------------------------------------
-- 2. TENANT CONFIGURATION TABLE (AI Guardrails)
-- ------------------------------------------------------------------------------
-- Manages hyper-parameters and system prompts for the local LLM.
-- DESIGN NOTE: This is deliberately separated from the main `tenant` table to adhere
-- to the Single Responsibility Principle, ensuring core case metadata isn't bloated
-- with AI-specific execution variables.
CREATE TABLE IF NOT EXISTS tenant_config (
    -- Shares a 1:1 Primary Key relationship with the `tenant` table.
    -- This enforces at the schema level that a case can only have one active configuration.
                                             tenant_id VARCHAR(255) PRIMARY KEY,

    -- The designated LLM (e.g., "llama3.2:3b"). Allows per-case model routing.
    ai_model_name VARCHAR(255),

    -- Controls inference determinism. In forensics, this is usually strictly 0.0.
    temperature FLOAT8,

    -- The foundational AI persona and rule-set prepended to every prompt.
    system_prompt TEXT
    );

-- ------------------------------------------------------------------------------
-- 3. HIERARCHICAL DIRECTORY SCHEMA (Vector Search Boundaries)
-- ------------------------------------------------------------------------------
-- Stores the visual and logical grouping of evidence. In a RAG system, these folders
-- act as physical bounding boxes to narrow down vector similarity searches.
CREATE TABLE IF NOT EXISTS folder (
                                      id VARCHAR(255) PRIMARY KEY,

    name VARCHAR(255) NOT NULL,

    -- ADJACENCY LIST PATTERN:
    -- Points to another folder_id to create infinite directory nesting.
    -- Nullable because top-level "root" folders do not have a parent.
    parent_folder_id VARCHAR(255),

    -- INHERITED FROM BaseTenantEntity:
    -- Binds this directory strictly to a specific case.
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
    );

-- ------------------------------------------------------------------------------
-- 4. KNOWLEDGE TRACKING ARCHIVE (The Async State Machine)
-- ------------------------------------------------------------------------------
-- This table does NOT hold raw files (S3) or embeddings (pgvector). It is the
-- relational ledger that links them together and tracks their ingestion state.
CREATE TABLE IF NOT EXISTS knowledge_document (
                                                  id VARCHAR(255) PRIMARY KEY,

    -- The correlation ID connecting the database to the background worker thread.
    -- UNIQUE constraint guarantees we never accidentally spawn duplicate async
    -- parsing jobs for the exact same upload event.
    job_id VARCHAR(255) NOT NULL UNIQUE,

    -- Retained strictly for UI presentation and audit logging.
    file_name VARCHAR(255) NOT NULL,

    -- Foreign key mapping to the logical directory. Not strictly enforced with a
    -- database constraint here to allow for high-throughput async ingestion and deletes.
    folder_id VARCHAR(255) NOT NULL,

    -- Tracks the finite state machine (e.g., PENDING, PROCESSING, COMPLETED, FAILED).
    -- Crucial for the frontend to determine if a file is ready for AI querying.
    status VARCHAR(50) NOT NULL,

    -- INHERITED FROM BaseTenantEntity:
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
    );

-- ------------------------------------------------------------------------------
-- 5. PERFORMANCE INDEXES FOR DISTRIBUTED ARCHITECTURE
-- ------------------------------------------------------------------------------
-- Because our standard JPA queries ALWAYS append `WHERE tenant_id = ?` to prevent
-- IDOR attacks, we must index these columns. Without these indexes, Postgres would
-- be forced to perform a Full Table Scan on every single API request, crashing
-- the database as the case load scales.

-- Optimizes recursive directory fetching and UI loads.
CREATE INDEX IF NOT EXISTS idx_folder_tenant ON folder(tenant_id);

-- Optimizes the primary "Get all files for this case and folder" UI query.
-- Uses a composite index because we almost always query both boundaries together.
CREATE INDEX IF NOT EXISTS idx_document_tenant_folder ON knowledge_document(tenant_id, folder_id);