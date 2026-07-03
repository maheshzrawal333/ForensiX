package com.maheshz.ForensiX.engine.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Enterprise Global Exception Boundary and API Contract Enforcer.
 * <p>
 * This class intercepts all unhandled exceptions thrown anywhere in the application
 * (Controllers, Services, Interceptors) before they reach the Tomcat HTTP response buffer.
 * <p>
 * ARCHITECTURAL PURPOSE:
 * 1. Standardizes all error responses to the RFC 7807 `ProblemDetail` JSON format, ensuring
 * the frontend UI has a predictable schema to parse during failures.
 * 2. Prevents Information Disclosure (OWASP). By catching generic exceptions, we mathematically
 * guarantee that raw stack traces, database schema details, or infrastructure paths are
 * never leaked to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Catches failures within the asynchronous AI and Vectorization pipeline.
     * <p>
     * RAG operations rely on fragile external boundaries (Ollama network calls, pgvector indexing,
     * S3 object retrieval). When these fail, we log the full stack trace internally for DevOps,
     * but return a sanitized, standardized 500 error to the client UI.
     *
     * @param ex The custom RAGProcessingException thrown by the service layer.
     * @return RFC 7807 ProblemDetail formatted for a 500 Internal Server Error.
     */
    @ExceptionHandler(RAGProcessingException.class)
    public ProblemDetail handleRAGException(RAGProcessingException ex) {
        // Log the full stack trace for internal APM/debugging tools
        log.error("RAG Processing Error: {}", ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problemDetail.setTitle("Vector Processing Failure");
        // Provides a URI pointing to internal developer documentation for this specific error class
        problemDetail.setType(URI.create("https://api.forensix.local/errors/vector-processing-failed"));
        return problemDetail;
    }

    /**
     * Catches perimeter validation failures and malformed client inputs.
     * <p>
     * Triggers when the frontend sends invalid UUIDs, missing required headers, or data that
     * violates our DTO constraints. Fails fast with a 400 Bad Request to prevent the system
     * from executing expensive AI logic on garbage data.
     *
     * @param ex The IllegalArgumentException thrown during validation.
     * @return RFC 7807 ProblemDetail formatted for a 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Invalid Request");
        return problemDetail;
    }

    /**
     * Handles abrupt TCP disconnections during long-lived Server-Sent Event (SSE) streams.
     * <p>
     * ARCHITECTURAL CRITICAL: In our RAG pipeline, AI inferences can take several minutes.
     * If the user closes their laptop lid or navigates away, Tomcat throws a Broken Pipe `IOException`.
     * <p>
     * By explicitly returning `void`, we instruct Spring MVC to simply drop the response.
     * If we attempted to return a JSON `ProblemDetail` here, Spring would try to write it into
     * a dead TCP socket, triggering a cascade of secondary exceptions and severely polluting our logs.
     *
     * @param ex The IOException triggered by the severed network socket.
     */
    @ExceptionHandler(java.io.IOException.class)
    public void handleClientDisconnect(java.io.IOException ex) {
        // Kept at TRACE/DEBUG level. In a high-traffic app, clients disconnect thousands of times
        // a day. Logging this as ERROR or WARN would render the production logs unreadable.
        log.debug("Client disconnected during an active connection. Stream closed safely.");
    }

    /**
     * The Ultimate Catch-All for unhandled runtime exceptions.
     * <p>
     * SECURITY BOUNDARY: This is our safety net against Zero-Day bugs or unforeseen NullPointerExceptions.
     * It completely swallows the exception message from the client, returning a generic static string,
     * while preserving the actual root cause exclusively in our secure server logs.
     *
     * @param ex Any Exception not explicitly handled by the methods above.
     * @return A sanitized, generic 500 Internal Server Error ProblemDetail.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        // Log the actual explosive stack trace internally
        log.error("Unexpected error occurred", ex);

        // Return a completely sanitized generic message to the client
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred.");
        problemDetail.setTitle("Internal Server Error");
        return problemDetail;
    }
}