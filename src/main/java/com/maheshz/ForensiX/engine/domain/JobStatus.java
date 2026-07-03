package com.maheshz.ForensiX.engine.domain;

/**
 * Enterprise State Machine Vocabulary for Asynchronous Tasks.
 * <p>
 * In the ForensiX architecture, heavy AI processing and document vectorization
 * are strictly decoupled from the main HTTP threads. This enumeration defines the
 * absolute, globally recognized lifecycle states of those background processes.
 * <p>
 * This enum acts as the contract for:
 * 1. Database persistence (tracking where a file is in the pipeline).
 * 2. Distributed locking (preventing two workers from claiming the same job).
 * 3. Frontend UX (dictating whether the UI shows a spinner, a progress bar, or an error).
 */
public enum JobStatus {

    /**
     * Initial State: The job has been securely accepted by the API (HTTP 202) and
     * persisted to the database, but is waiting in the queue.
     * <p>
     * Implication: No CPU/Memory resources have been allocated yet. The frontend
     * should display a "Queued" indicator or a spinning loader.
     */
    PENDING,

    /**
     * Active State: A background AI worker thread has successfully claimed a lock on this
     * job and is actively executing it (e.g., Apache Tika extraction, LLM vector embedding).
     * <p>
     * Implication: The worker is now actively emitting Server-Sent Events (SSE) to Redis.
     * The frontend should transition to displaying a real-time progress bar.
     */
    PROCESSING,

    /**
     * Terminal State (Success): The pipeline has finished. All semantic chunks are safely
     * committed to PostgreSQL (pgvector), and the file is instantly available for RAG queries.
     * <p>
     * Implication: All memory buffers, S3 streams, and Redis Pub/Sub channels associated
     * with this job ID must be garbage collected.
     */
    COMPLETED,

    /**
     * Terminal State (Error): An unrecoverable system exception occurred
     * (e.g., OutOfMemory error, corrupted PDF binary, or Ollama local network failure).
     * <p>
     * Implication: Triggers a transactional rollback. Any partially generated vector chunks
     * associated with this job must be aggressively purged from the database to prevent
     * "ghost evidence" from contaminating future AI queries.
     */
    FAILED
}