package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.config.MetadataConstants;
import com.maheshz.openrag.engine.controller.ProgressController;
import com.maheshz.openrag.engine.domain.JobStatus;
import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Service
public class AsyncIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionWorker.class);
    private static final int EMBEDDING_BATCH_SIZE = 50;

    private final VectorStore vectorStore;
    private final ProgressController progressController;
    private final DocumentRepository documentRepository;

    public AsyncIngestionWorker(VectorStore vectorStore,
                                ProgressController progressController,
                                DocumentRepository documentRepository) {
        this.vectorStore = vectorStore;
        this.progressController = progressController;
        this.documentRepository = documentRepository;
    }

    @Async("aiTaskExecutor")
    @Transactional // Required because we are calling a custom @Modifying query
    public void processFileAsync(File file, KnowledgeDocument tracker) {
        String jobId = tracker.getJobId();
        String fileName = tracker.getFileName().toLowerCase();
        long processStartTime = System.currentTimeMillis();

        try {
            documentRepository.updateJobStatus(tracker.getId(), JobStatus.PROCESSING);
            List<Document> chunkedDocuments;

            if (fileName.endsWith(".csv")) {
                progressController.emitProgress(jobId, "Tabular data detected. Executing memory-safe semantic CSV extraction...");
                chunkedDocuments = extractCsvSemantically(file);
            } else {
                progressController.emitProgress(jobId, "Parsing structural binary components via Apache Tika...");
                TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(file));
                List<Document> rawDocuments = documentReader.get();

                progressController.emitProgress(jobId, "Executing localized token splitting algorithm...");
                TokenTextSplitter textSplitter = new TokenTextSplitter(500, 350, 50, 10000, true);
                chunkedDocuments = textSplitter.apply(rawDocuments);
            }

            int totalChunks = chunkedDocuments.size();
            progressController.emitProgress(jobId, "Target parsed successfully into " + totalChunks + " discrete chunks.");

            chunkedDocuments.forEach(doc -> {
                doc.getMetadata().put(MetadataConstants.TENANT_ID, tracker.getTenantId());
                doc.getMetadata().put(MetadataConstants.FOLDER_ID, tracker.getFolderId());
                doc.getMetadata().put(MetadataConstants.DOCUMENT_ID, tracker.getId());
                doc.getMetadata().put("file_name", tracker.getFileName()); // Crucial for UI citations
            });

            log.info("Beginning vectorized partitioning for job {}. Total blocks: {}", jobId, totalChunks);

            for (int i = 0; i < totalChunks; i += EMBEDDING_BATCH_SIZE) {
                int endOffset = Math.min(i + EMBEDDING_BATCH_SIZE, totalChunks);
                List<Document> subBatch = chunkedDocuments.subList(i, endOffset);

                vectorStore.add(subBatch);

                int processedCount = endOffset;
                int percentComplete = (int) (((double) processedCount / totalChunks) * 100);
                long elapsedDurationMs = System.currentTimeMillis() - processStartTime;

                double msPerChunk = (double) elapsedDurationMs / processedCount;
                long totalRemainingChunks = totalChunks - processedCount;
                long estimatedTimeRemainingMs = (long) (totalRemainingChunks * msPerChunk);

                String progressTelemetry = String.format(
                        "Progress: %d/%d vector blocks committed (%d%%) | Elapsed: %s | ETA: %s",
                        processedCount, totalChunks, percentComplete, formatDuration(elapsedDurationMs), formatDuration(estimatedTimeRemainingMs)
                );
                progressController.emitProgress(jobId, progressTelemetry);
            }

            documentRepository.updateJobStatus(tracker.getId(), JobStatus.COMPLETED);
            progressController.emitProgress(jobId, "Complete");
            log.info("Job {} processing successfully finalized.", jobId);

        } catch (Exception e) {
            log.error("Fatal exception caught during execution workflow for job {}", jobId, e);
            documentRepository.updateJobStatus(tracker.getId(), JobStatus.FAILED);
            progressController.emitProgress(jobId, "Pipeline Failure: " + e.getMessage());
        } finally {
            cleanupTempFile(file);
            progressController.completeEmitter(jobId);
        }
    }

    /**
     * SENIOR FIX: Uses Apache Commons CSV. Memory-safe, highly optimized, and immune to Regex Backtracking limits.
     */
    private List<Document> extractCsvSemantically(File file) {
        List<Document> documents = new ArrayList<>();
        try (Reader reader = new FileReader(file);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            List<String> headers = csvParser.getHeaderNames();
            StringBuilder chunkBuilder = new StringBuilder();
            int rowIndex = 1;

            for (CSVRecord record : csvParser) {
                chunkBuilder.append("Record ").append(rowIndex).append(": ");
                for (String header : headers) {
                    if (record.isSet(header)) {
                        chunkBuilder.append(header).append(" is ").append(record.get(header)).append(", ");
                    }
                }
                chunkBuilder.append(". \n");

                if (rowIndex % 10 == 0) {
                    documents.add(new Document(chunkBuilder.toString()));
                    chunkBuilder = new StringBuilder();
                }
                rowIndex++;
            }

            if (!chunkBuilder.isEmpty()) {
                documents.add(new Document(chunkBuilder.toString()));
            }

        } catch(Exception e) {
            log.error("Failed to parse semantic CSV", e);
        }
        return documents;
    }

    private void cleanupTempFile(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            log.warn("Failed to delete temp file: {}", file.getAbsolutePath());
        }
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long totalSeconds = ms / 1000;
        if (totalSeconds < 1) return ms + "ms";

        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (minutes == 0) return seconds + "s";
        return minutes + "m " + seconds + "s";
    }
}