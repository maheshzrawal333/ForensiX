package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.controller.ProgressController;
import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.exception.RAGProcessingException;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

@Service
public class AsyncIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionWorker.class);

    private final VectorStore vectorStore;
    private final ProgressController progressController;
    private final DocumentRepository documentRepository; // <-- Added Repository

    public AsyncIngestionWorker(VectorStore vectorStore, ProgressController progressController, DocumentRepository documentRepository) {
        this.vectorStore = vectorStore;
        this.progressController = progressController;
        this.documentRepository = documentRepository;
    }

    @Async("aiTaskExecutor")
    public void processFile(File file, String tenantId, String folderId, String jobId) {
        // 1. Create the tracking record in the database
        KnowledgeDocument tracker = new KnowledgeDocument();
        tracker.setId(UUID.randomUUID().toString());
        tracker.setJobId(jobId);
        tracker.setTenantId(tenantId);
        tracker.setFolderId(folderId);
        tracker.setFileName(file.getName().replace("ingest-", "")); // Cleanup temp prefix
        tracker.setStatus("PROCESSING");
        documentRepository.save(tracker);

        try {
            log.info("Starting ingestion job {} for tenant {}", jobId, tenantId);
            progressController.emitProgress(jobId, "Parsing File...");

            TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(file));
            List<Document> rawDocuments = documentReader.get();

            progressController.emitProgress(jobId, "Chunking Content...");
            TokenTextSplitter textSplitter = new TokenTextSplitter(500, 350, 50, 10000, true);
            List<Document> chunkedDocuments = textSplitter.apply(rawDocuments);

            progressController.emitProgress(jobId, "Generating Embeddings & Saving Vector Store...");
            for (Document doc : chunkedDocuments) {
                doc.getMetadata().put("tenant_id", tenantId);
                doc.getMetadata().put("folder_id", folderId);
                doc.getMetadata().put("document_id", tracker.getId());
            }

            vectorStore.add(chunkedDocuments);

            // 2. Mark as successfully completed
            tracker.setStatus("COMPLETED");
            documentRepository.save(tracker);

            progressController.emitProgress(jobId, "Complete");
            log.info("Job {} completed successfully.", jobId);

        } catch (Exception e) {
            log.error("Error processing file for job {}", jobId, e);

            // 3. Mark as failed if anything crashes
            tracker.setStatus("FAILED");
            documentRepository.save(tracker);

            progressController.emitProgress(jobId, "Error: " + e.getMessage());
            throw new RAGProcessingException("Failed to process document ingestion for job: " + jobId, e);
        } finally {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (Exception e) {
                log.warn("Failed to delete temp file {}", file.getAbsolutePath());
            }
            progressController.completeEmitter(jobId);
        }
    }
}