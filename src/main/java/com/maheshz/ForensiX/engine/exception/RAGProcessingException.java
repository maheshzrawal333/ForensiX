package com.maheshz.ForensiX.engine.exception;

/**
 * Core Domain Exception for Vector Ingestion and AI Inference Failures.
 * <p>
 * This exception serves as our dedicated semantic boundary for any unrecoverable errors
 * that occur within the Retrieval-Augmented Generation (RAG) pipeline.
 * By encapsulating these specific runtime failures here, we decouple AI/vector infrastructure
 * bugs from standard relational database or web layer exceptions.
 * <p>
 * Typical trigger scenarios include:
 * <ul>
 * <li>Ollama local API connection timeouts or model-weight loading failures.</li>
 * <li>pgvector dimensional mismatched vector insertion errors.</li>
 * <li>Apache Tika structural text extraction failures on corrupted evidence binaries.</li>
 * <li>Token window overflow limits during prompt synthesis.</li>
 * </ul>
 * <p>
 * DESIGN CHOICE: Extends {@link RuntimeException} to adhere to modern clean architecture patterns.
 * Unchecked exceptions eliminate boilerplate {@code throws} signatures across our services and interfaces,
 * allowing Spring's transactional boundary management and our {@code GlobalExceptionHandler}
 * to intercept the failure cleanly at the perimeter.
 */
public class RAGProcessingException extends RuntimeException {

    /**
     * Constructs a new exception with a specific, human-readable error message.
     * Use this constructor when a failure condition is explicitly identified and handled
     * within our business logic (e.g., a requested AI model is not on our whitelist).
     *
     * @param message A descriptive failure message that will eventually be sanitized
     * and formatted into an RFC 7807 ProblemDetail response for the UI.
     */
    public RAGProcessingException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception by wrapping a low-level root cause.
     * Use this constructor to preserve the original stack trace when catching checked
     * third-party infrastructure exceptions (e.g., catching a {@code ConnectException}
     * from the HTTP client communicating with Ollama).
     * <p>
     * Tracing the root cause is absolutely critical for our DevOps and APM logging platforms
     * to distinguish between hardware starvation, network failures, and bad input payloads.
     *
     * @param message A high-level context message explaining what the pipeline was trying to achieve.
     * @param cause   The original underlying exception (the root cause) thrown by low-level libraries.
     */
    public RAGProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}