package com.maheshz.ForensiX.engine.service.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;

/**
 * Enterprise Object Storage Adapter and Lifecycle Manager.
 * <p>
 * This service implements the primary persistence boundary for raw binary evidence
 * (PDFs, system logs, disk images). It interfaces with AWS S3 (or S3-compatible
 * local storage like MinIO) using the AWS SDK v2.
 * <p>
 * ARCHITECTURAL INVARIANTS:
 * 1. Stream-First: To protect JVM Heap memory, files are never loaded completely into byte arrays.
 * 2. Deterministic Taxonomy: Every object path strictly enforces multi-tenant isolation.
 * 3. Ephemeral Resilience: The service must survive and heal from underlying volume wipes.
 */
@Service
public class S3StorageServiceImpl implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageServiceImpl.class);

    private final S3Client s3Client;
    private final String bucketName;

    /**
     * Constructor injection ensures the S3 client and configuration are immutable.
     */
    public S3StorageServiceImpl(S3Client s3Client, @Value("${cloud.aws.s3.bucket-name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * Infrastructure Self-Healing Hook.
     * <p>
     * Executed automatically by the Spring container immediately after bean instantiation.
     * In a containerized ecosystem (e.g., Docker Compose), local MinIO volumes are frequently
     * destroyed and recreated. Rather than requiring developers or deployment scripts to
     * manually provision buckets, this method guarantees the required infrastructure exists
     * before the application starts accepting traffic.
     */
    @PostConstruct
    public void initializeBucket() {
        try {
            log.info("Verifying cloud storage bucket '{}'...", bucketName);
            // Attempt to fetch bucket metadata. If it exists, this silently succeeds.
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            log.info("Cloud storage bucket '{}' is ready.", bucketName);
        } catch (NoSuchBucketException e) {
            // Graceful degradation: The infrastructure is missing, so we provision it on the fly.
            log.warn("Bucket '{}' missing (likely due to fresh Docker environment). Creating...", bucketName);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            log.info("Cloud storage bucket '{}' successfully provisioned.", bucketName);
        } catch (Exception e) {
            // A fatal exception here means the S3 endpoint is entirely unreachable or credentials are bad.
            // We log the error, but do not crash the application, allowing other non-storage features to function.
            log.error("Fatal error verifying S3 storage bucket. Uploads will fail.", e);
        }
    }

    /**
     * Streams a raw binary payload directly to object storage.
     * <p>
     * ARCHITECTURAL DESIGN (Memory Safety):
     * Forensic evidence can be massive (e.g., 5GB database dumps). This method specifically
     * extracts the raw {@code InputStream} from the MultipartFile and passes it to the AWS SDK.
     * This establishes a direct TCP pipe from the Tomcat web server to S3, bypassing JVM heap allocation.
     *
     * @param file The multi-part file received from the HTTP boundary.
     * @param tenantId The validated boundary ID for the active investigative case.
     * @param documentId The UUID of the database record representing this file.
     * @return The absolute S3 Object Key (used downstream for retrieval and deletion).
     */
    @Override
    public String uploadFile(MultipartFile file, String tenantId, String documentId) {

        // -----------------------------------------------------------
        // PHYSICAL MULTI-TENANCY TAXONOMY
        // -----------------------------------------------------------
        // By structuring the key as {tenantId}/{documentId}/{filename}, we physically
        // partition the data at the storage layer. This ensures that even if an attacker
        // bypassed the database, they could not easily traverse a flat S3 bucket structure.
        String objectKey = tenantId + "/" + documentId + "/" + file.getOriginalFilename();

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .build();

            // Push the stream directly over the wire
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            log.info("Successfully uploaded evidence to S3 Cloud: {}", objectKey);
            return objectKey;

        } catch (Exception e) {
            log.error("Cloud storage upload failed for file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to upload evidence to S3.", e);
        }
    }

    /**
     * Opens a read-only stream to an existing S3 object.
     * <p>
     * PERFORMANCE NOTE:
     * This does NOT download the file into local memory. It returns a live HTTP stream.
     * The downstream consumer (e.g., Apache Tika parser) is strictly responsible for
     * buffering, consuming, and eventually closing this stream to prevent socket leaks.
     *
     * @param objectKey The exact physical path generated during the upload phase.
     * @return A live InputStream pointing to the S3 object payload.
     */
    @Override
    public InputStream downloadFile(String objectKey) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            return s3Client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Failed to stream file from S3: {}", objectKey, e);
            throw new RuntimeException("Failed to download file from S3.", e);
        }
    }

    /**
     * Permanently purges a binary object from the storage cluster.
     * <p>
     * IDEMPOTENCY DESIGN:
     * This method is designed to fail silently if the file is already missing.
     * In distributed systems, retries or cascade-delete operations might attempt to delete
     * the same object twice. Throwing a hard exception here could unnecessarily abort a
     * larger database transaction.
     *
     * @param objectKey The exact physical path to obliterate.
     */
    @Override
    public void deleteFile(String objectKey) {
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.debug("Successfully purged temporary file {} from S3", objectKey);
        } catch (Exception e) {
            // Log as a warning rather than an error, as this is often a harmless race condition
            // or the result of a self-cleaning lifecycle rule in S3.
            log.warn("Failed to delete file from S3 (might already be deleted): {}", objectKey, e);
        }
    }
}