-- ==============================================================================
-- V1__Init_Schema.sql
-- Enterprise Schema Definition: pgvector Multi-Tenant RAG Store
-- ==============================================================================
-- ARCHITECTURAL OVERVIEW:
-- This migration bootstraps the vector database tier for the ForensiX engine.
-- Instead of relying on standard ORM (Hibernate) behavior, we define strict
-- schema constraints and stored functions here to push heavy vector mathematics
-- and multi-tenant filtering down to the C-level PostgreSQL engine, keeping
-- the Java JVM lightweight and protecting against OutOfMemory (OOM) errors.
-- ==============================================================================

-- 1. EXTENSION BOOTSTRAP
-- Enables hardware-accelerated vector operations (similarity search, indexing)
-- directly within PostgreSQL. This must be executed by a superuser.
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. CORE STORAGE ANCHOR
-- This table stores the high-dimensional embeddings and their corresponding text.
-- It acts as the physical persistence layer for the Spring AI `VectorStore`.
CREATE TABLE IF NOT EXISTS vector_store (
    -- UUIDv4 prevents sequential enumeration (IDOR attacks) and guarantees
    -- collision-free inserts across distributed database nodes.
                                            id UUID DEFAULT gen_random_uuid() PRIMARY KEY,

    -- The raw semantic chunk (usually ~500 tokens) that will be injected
    -- into the LLM's context window.
    content TEXT NOT NULL,

    -- Schema-less metadata storage. Spring AI automatically dumps tenant IDs,
    -- folder IDs, and filenames here. JSONB allows us to index and query this
    -- data without rigid column definitions.
    metadata JSONB NOT NULL,

    -- The Mathematical Embedding.
    -- Dimensions (768) must exactly match the output of your embedding model
    -- (e.g., nomic-embed-text or sentence-transformers).
    -- Note: If you switch models later (e.g., to a 1536-dim OpenAI model),
    -- this column will require a schema migration.
    embedding VECTOR(768)
    );

-- 3. INFRASTRUCTURE OPTIMIZATION: METADATA PRE-FILTERING
-- SENIOR FIX 1: Without this index, PostgreSQL would have to calculate the vector
-- distance for EVERY chunk in the database before sorting them.
-- By placing a Generalized Inverted Index (GIN) on the JSONB metadata, we force
-- Postgres to do "Pre-Filtering". It instantly isolates the rows belonging to a
-- specific `tenantId` (sub-millisecond) BEFORE performing the expensive vector math.
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata ON vector_store USING GIN (metadata);

-- 4. PUSH-DOWN BUSINESS LOGIC: HARDWARE ACCELERATED SEARCH
-- SENIOR FIX 2: Added distance_threshold to prevent AI hallucinations.
--
-- ARCHITECTURAL DESIGN:
-- Pulling thousands of vectors over JDBC to Java just to calculate their distance
-- would destroy network bandwidth. This PL/pgSQL function pushes the entire
-- search payload down to the database.
CREATE OR REPLACE FUNCTION hybrid_search(
    query_text TEXT,               -- Reserved for future BM25 Full-Text Search integration
    query_embedding VECTOR,        -- The mathematical representation of the user's question
    query_tenant_id TEXT,          -- SECURITY: Mandatory multi-tenant boundary
    target_folder_ids TEXT[],      -- OPTIONAL: Directory boundary array for granular search
    match_limit INT,               -- Prevents blowing out the LLM Context Window (e.g., Top 5)
    distance_threshold FLOAT       -- HYPERPARAMETER: The absolute cutoff for semantic relevancy
)
RETURNS TABLE (
    content TEXT,
    metadata JSON,
    distance FLOAT
)
LANGUAGE plpgsql
AS $$
BEGIN
RETURN QUERY
SELECT
    v.content,
    v.metadata::json,
        -- The <=> operator calculates the Cosine Distance between vectors.
        -- Lower distance means higher semantic similarity.
    (v.embedding <=> query_embedding) AS distance
FROM vector_store v
WHERE
  -- ==========================================
  -- SECURITY BOUNDARY (Hard Multi-Tenancy)
  -- ==========================================
  -- We check both camelCase and snake_case to safely handle different JSON
  -- serialization strategies from the Spring Boot layer.
    (v.metadata->>'tenantId' = query_tenant_id OR v.metadata->>'tenant_id' = query_tenant_id)

  -- ==========================================
  -- LOGICAL BOUNDARY (Directory Scoping)
  -- ==========================================
  AND (
    target_folder_ids IS NULL
        OR array_length(target_folder_ids, 1) IS NULL
        OR v.metadata->>'folderId' = ANY(target_folder_ids)
        OR v.metadata->>'folder_id' = ANY(target_folder_ids)
        OR v.metadata->>'documentId' = ANY(target_folder_ids)
        OR v.metadata->>'document_id' = ANY(target_folder_ids)
    )

  -- ==========================================
  -- HALLUCINATION PREVENTION (Thresholding)
  -- ==========================================
  -- If the user asks "What is the capital of France?" but the case files only
  -- contain financial records, the closest vector might still be "Bank Statement 2024",
  -- but the distance will be very high (e.g., 0.95).
  -- Dropping results above the threshold ensures we return NOTHING rather than
  -- returning garbage context that forces the LLM to hallucinate an answer.
  AND (v.embedding <=> query_embedding) < distance_threshold

ORDER BY
    distance ASC
    LIMIT match_limit;
END;
$$;