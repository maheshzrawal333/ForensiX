package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.domain.JobStatus;
import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.exception.RAGProcessingException;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class IngestionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

    private final AsyncIngestionWorker asyncIngestionWorker;
    private final DocumentRepository documentRepository;

    public IngestionOrchestrator(AsyncIngestionWorker asyncIngestionWorker, DocumentRepository documentRepository) {
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.documentRepository = documentRepository;
    }

    public String initiateFileUpload(MultipartFile file, String tenantId, String folderId) {
        // SENIOR SECURITY FIX: Strip malicious path climbing characters (e.g., ../../../)
        String rawFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown_file";
        String sanitizedFilename = Paths.get(rawFilename).getFileName().toString();

        if (documentRepository.existsByFileNameAndFolderIdAndTenantId(sanitizedFilename, folderId, tenantId)) {
            log.warn("Upload rejected: Document '{}' already exists in case '{}'.", sanitizedFilename, tenantId);
            throw new IllegalArgumentException("File '" + sanitizedFilename + "' already exists in this case.");
        }

        String jobId = UUID.randomUUID().toString();

        KnowledgeDocument tracker = new KnowledgeDocument();
        tracker.setJobId(jobId);
        tracker.setTenantId(tenantId);
        tracker.setFolderId(folderId);
        tracker.setFileName(sanitizedFilename); // Use secured name
        tracker.setStatus(JobStatus.PENDING);

        KnowledgeDocument savedTracker = documentRepository.saveAndFlush(tracker);

        // Pass the secured name to the temp file creator
        File tempFile = createTempFile(file, sanitizedFilename);

        log.info("Dispatched async ingestion job: {} for file: {}", jobId, sanitizedFilename);
        asyncIngestionWorker.processFileAsync(tempFile, savedTracker);

        return jobId;
    }

    private File createTempFile(MultipartFile file, String sanitizedFilename) {
        try {
            Path tempPath = Files.createTempFile("ingest-", "-" + sanitizedFilename);
            Files.copy(file.getInputStream(), tempPath, StandardCopyOption.REPLACE_EXISTING);
            return tempPath.toFile();
        } catch (Exception e) {
            throw new RAGProcessingException("Failed to store uploaded file securely.", e);
        }
    }
}