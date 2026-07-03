package com.maheshz.ForensiX.engine.service;

import com.maheshz.ForensiX.engine.config.MetadataConstants;
import com.maheshz.ForensiX.engine.config.RedisPubSubConfig;
import com.maheshz.ForensiX.engine.domain.JobStatus;
import com.maheshz.ForensiX.engine.domain.KnowledgeDocument;
import com.maheshz.ForensiX.engine.repository.DocumentRepository;
import com.maheshz.ForensiX.engine.repository.VectorCleanupRepository;
import com.maheshz.ForensiX.engine.service.storage.StorageService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Enterprise Asynchronous Ingestion & Vectorization Engine.
 * <p>
 * This worker manages the high-latency, compute-intensive pipeline required to transform
 * raw forensic binary files into high-dimensional vector embeddings for RAG (Retrieval-Augmented Generation).
 * <p>
 * ARCHITECTURAL DESIGN:
 * To maintain high availability, this service runs entirely on a dedicated thread pool
 * (`aiTaskExecutor`). It operates via a "Pull-and-Push" model: pulling streams from
 * S3, parsing them, and pushing embeddings into `pgvector`.
 */
@Service
public class AsyncIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionWorker.class);

    /**
     * Hyperparameter: Tuning the throughput of the vector database.
     * Higher values increase ingestion speed but elevate the risk of hitting
     * database transaction timeouts or token-limit exhaustion.
     */
    private static final int EMBEDDING_BATCH_SIZE = 5;

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final VectorCleanupRepository vectorCleanupRepository;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    public AsyncIngestionWorker(VectorStore vectorStore,
                                DocumentRepository documentRepository,
                                VectorCleanupRepository vectorCleanupRepository,
                                StorageService storageService,
                                StringRedisTemplate redisTemplate) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
        this.vectorCleanupRepository = vectorCleanupRepository;
        this.storageService = storageService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Executes the end-to-end ingestion pipeline for an uploaded artifact.
     * <p>
     * WORKFLOW:
     * 1. Download: Stream binary from S3 directly to RAM.
     * 2. Parse/Extract: Utilize Apache Tika for binaries or custom CSV parsers for tabular data.
     * 3. Chunk: Tokenize text to fit within the LLM context window.
     * 4. Embed: Generate high-dimensional vectors and push to pgvector.
     * 5. Finalize: Update job state in relational DB.
     *
     * @param objectKey The S3 URI provided by the storage service.
     * @param tracker The relational record tracking this job's lifecycle status.
     */
    @Async("aiTaskExecutor")
    public void processFileAsync(String objectKey, KnowledgeDocument tracker) {
        String jobId = tracker.getJobId();
        String fileName = tracker.getFileName().toLowerCase();
        long processStartTime = System.currentTimeMillis();

        // -----------------------------------------------------------
        // 1. STREAMING ACCESS: Essential for Memory Safety
        // -----------------------------------------------------------
        try (InputStream cloudStream = storageService.downloadFile(objectKey)) {
            documentRepository.updateJobStatus(tracker.getId(), JobStatus.PROCESSING);

            // ==========================================
            // BRANCH 1: TABULAR CSV (Semantic Extraction)
            // ==========================================
            if (fileName.endsWith(".csv")) {
                broadcastProgress(jobId, "Tabular data detected. Executing memory-safe semantic CSV extraction...");
                extractCsvSemantically(cloudStream, tracker);
                broadcastProgress(jobId, "Tabular processing and vector embedding complete.");

                // ==========================================
                // BRANCH 2: UNSTRUCTURED FILES (PDF, Word, TXT)
                // ==========================================
            } else {
                broadcastProgress(jobId, "Downloading stream and parsing structural binary components via Apache Tika...");
                Resource resource = new InputStreamResource(cloudStream);
                TikaDocumentReader documentReader = new TikaDocumentReader(resource);
                List<Document> rawDocuments = documentReader.get();

                broadcastProgress(jobId, "Executing localized token splitting algorithm...");
                TokenTextSplitter textSplitter = new TokenTextSplitter(500, 350, 50, 10000, true);
                List<Document> chunkedDocuments = textSplitter.apply(rawDocuments);

                int totalChunks = chunkedDocuments.size();
                broadcastProgress(jobId, "Target parsed successfully into " + totalChunks + " discrete chunks.");

                // -----------------------------------------------------------
                // 2. METADATA STAMPING: Enforcing Multi-Tenancy
                // -----------------------------------------------------------
                // We inject the Tenant and Folder ID into every metadata block.
                // This is the "secret sauce" that allows our HybridSearchRepository
                // to filter searches by case or folder instantly.
                chunkedDocuments.forEach(doc -> {
                    doc.getMetadata().put(MetadataConstants.TENANT_ID, tracker.getTenantId());
                    doc.getMetadata().put(MetadataConstants.FOLDER_ID, tracker.getFolderId());
                    doc.getMetadata().put(MetadataConstants.DOCUMENT_ID, tracker.getId());
                    doc.getMetadata().put("file_name", tracker.getFileName());
                });

                log.info("Beginning vectorized partitioning for job {}. Total blocks: {}", jobId, totalChunks);

                // Manual batch-embedding loop for performance monitoring
                for (int i = 0; i < totalChunks; i += EMBEDDING_BATCH_SIZE) {
                    int endOffset = Math.min(i + EMBEDDING_BATCH_SIZE, totalChunks);
                    List<Document> subBatch = chunkedDocuments.subList(i, endOffset);

                    vectorStore.add(subBatch);

                    // -----------------------------------------------------------
                    // 3. REAL-TIME PROGRESS TELEMETRY
                    // -----------------------------------------------------------
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
                    broadcastProgress(jobId, progressTelemetry);
                }
            }

            documentRepository.updateJobStatus(tracker.getId(), JobStatus.COMPLETED);
            log.info("Job {} processing successfully finalized.", jobId);

            // -----------------------------------------------------------
            // 4. TRANSACTIONAL ROLLBACK: Ghost Vector Prevention
            // -----------------------------------------------------------
        } catch (Exception e) {
            log.error("Fatal exception caught during execution workflow for job {}", jobId, e);
            documentRepository.updateJobStatus(tracker.getId(), JobStatus.FAILED);
            // If the process fails halfway, wipe out the orphaned embeddings to keep the vector database clean
            vectorCleanupRepository.wipeVectorsByDocumentId(tracker.getId(), tracker.getTenantId());
            log.warn("Rolled back partial vectors for failed document: {}", tracker.getId());
            broadcastProgress(jobId, "Pipeline Failure: " + e.getMessage());
        } finally {
            // Signal termination to the SSE stream. Note: We retain the physical file in S3 for evidence retention.
            broadcastComplete(jobId);
        }
    }

    /**
     * Parses CSV rows into semantic chunks using a "sliding window" approach.
     * This method is specifically optimized for memory consumption by flushing
     * results to the vector store every 100 records.
     */
    private List<Document> extractCsvSemantically(InputStream inputStream, KnowledgeDocument tracker) {
        List<Document> memoryBuffer = new ArrayList<>();
        int batchSize = 100;
        int totalProcessed = 0;

        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
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

                // Batching rows to create a semantic block
                if (rowIndex % 10 == 0) {
                    Document doc = new Document(chunkBuilder.toString());
                    doc.getMetadata().put(MetadataConstants.TENANT_ID, tracker.getTenantId());
                    doc.getMetadata().put(MetadataConstants.FOLDER_ID, tracker.getFolderId());
                    doc.getMetadata().put(MetadataConstants.DOCUMENT_ID, tracker.getId());
                    doc.getMetadata().put("file_name", tracker.getFileName());

                    memoryBuffer.add(doc);
                    chunkBuilder = new StringBuilder();
                }

                // Periodic flushing to memory/db management
                if (memoryBuffer.size() >= batchSize) {
                    vectorStore.add(memoryBuffer);
                    totalProcessed += memoryBuffer.size();
                    memoryBuffer.clear();
                    broadcastProgress(tracker.getJobId(), "Streaming tabular data... embedded " + totalProcessed + " chunks.");
                }
                rowIndex++;
            }

            // Flush remaining data
            if (!chunkBuilder.isEmpty()) {
                Document doc = new Document(chunkBuilder.toString());
                doc.getMetadata().put(MetadataConstants.TENANT_ID, tracker.getTenantId());
                doc.getMetadata().put(MetadataConstants.FOLDER_ID, tracker.getFolderId());
                doc.getMetadata().put(MetadataConstants.DOCUMENT_ID, tracker.getId());
                doc.getMetadata().put("file_name", tracker.getFileName());
                memoryBuffer.add(doc);
            }
            if (!memoryBuffer.isEmpty()) {
                vectorStore.add(memoryBuffer);
            }

        } catch(Exception e) {
            log.error("Failed to parse semantic CSV", e);
            throw new RuntimeException("CSV Parsing Failed", e);
        }

        return new ArrayList<>();
    }

    private void broadcastProgress(String jobId, String message) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, message);
    }

    private void broadcastComplete(String jobId) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, "Complete");
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