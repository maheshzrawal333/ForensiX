package com.maheshz.ForensiX.engine.service.storage;

import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;

/**
 * Enterprise Storage Port (Hexagonal Architecture Boundary).
 * <p>
 * This interface defines the strict contract for binary payload persistence within the
 * ForensiX platform. It decouples the core forensic and AI business logic from the underlying
 * infrastructure (e.g., AWS S3, MinIO, or Local Filesystem).
 * <p>
 * ARCHITECTURAL INVARIANTS FOR ALL IMPLEMENTATIONS:
 * 1. Stream-First Memory Management: Massive forensic files (e.g., 10GB disk images) must
 * never be buffered entirely into the JVM heap. Implementations must route streams directly.
 * 2. Cryptographic Isolation: Every implementation must mathematically enforce multi-tenancy
 * using the provided `tenantId` to prevent cross-case data contamination.
 */
public interface StorageService {

    /**
     * Persists a raw binary payload into isolated cloud storage.
     * <p>
     * CONTRACT: Implementations must guarantee that the resulting object path is physically
     * partitioned by the {@code tenantId}. If the upload is interrupted, the implementation
     * should fail cleanly rather than leaving a corrupted, partial file.
     *
     * @param file The multi-part file stream originating from the HTTP boundary.
     * @param tenantId The validated Case ID, acting as the root partition for the file.
     * @param documentId The UUID correlating this binary to its relational metadata record.
     * @return The absolute, implementation-specific Object Key/URI required to retrieve or delete the file later.
     * @throws RuntimeException (or a specific storage exception) if network connectivity drops or limits are exceeded.
     */
    String uploadFile(MultipartFile file, String tenantId, String documentId);

    /**
     * Opens a read-only stream to a persisted binary object.
     * <p>
     * CONSUMER RESPONSIBILITY (MEMORY LEAK WARNING):
     * This method returns a live, open network/disk stream. The caller (e.g., the AI vectorization
     * worker or Apache Tika parser) is strictly responsible for wrapping this call in a
     * {@code try-with-resources} block to ensure the socket is closed when parsing completes.
     *
     * @param objectKey The exact URI/Path generated during the {@code uploadFile} phase.
     * @return A live InputStream pointing to the object payload.
     * @throws RuntimeException if the object does not exist or the tenant lacks access.
     */
    InputStream downloadFile(String objectKey);

    /**
     * Permanently obliterates a file from the underlying storage infrastructure.
     * <p>
     * IDEMPOTENCY CONTRACT:
     * Implementations of this method MUST be idempotent. If a caller attempts to delete an
     * {@code objectKey} that has already been deleted (or never existed), the implementation
     * should log a warning but return normally. Throwing an exception here risks rolling back
     * larger distributed transactions (like database cascading deletes) unnecessarily.
     *
     * @param objectKey The exact URI/Path of the file to be purged.
     */
    void deleteFile(String objectKey);
}