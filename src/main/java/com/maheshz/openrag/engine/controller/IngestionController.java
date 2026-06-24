package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.controller.dto.UploadResponse;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import com.maheshz.openrag.engine.security.TenantContextHolder;
import com.maheshz.openrag.engine.service.AsyncIngestionWorker;
import com.maheshz.openrag.engine.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final AsyncIngestionWorker asyncIngestionWorker;
    private final DocumentRepository documentRepository;
    private final IngestionService ingestionService;

    // Inject all required services and repositories here
    public IngestionController(AsyncIngestionWorker asyncIngestionWorker,
                               DocumentRepository documentRepository,
                               IngestionService ingestionService) {
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
    }

    @PostMapping("/text")
    public ResponseEntity<String> ingestTextData(
            @RequestParam String folderId,
            @RequestBody String content) {

        ingestionService.ingestText(content, folderId);
        return ResponseEntity.ok("Data successfully vectorized and stored.");
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("folderId") String folderId) {

        String tenantId = TenantContextHolder.getTenantId();
        String jobId = UUID.randomUUID().toString();

        try {
            Path tempPath = Files.createTempFile("ingest-", "-" + file.getOriginalFilename());
            Files.copy(file.getInputStream(), tempPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            File tempFile = tempPath.toFile();

            log.info("Dispatched async ingestion job: {}", jobId);
            asyncIngestionWorker.processFile(tempFile, tenantId, folderId, jobId);

            return ResponseEntity.accepted().body(new UploadResponse(jobId));
        } catch (Exception e) {
            log.error("Failed to initiate upload for job {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<com.maheshz.openrag.engine.domain.KnowledgeDocument> checkUploadStatus(@PathVariable String jobId) {
        return documentRepository.findByJobId(jobId)
                .map(doc -> ResponseEntity.ok(doc))
                .orElse(ResponseEntity.notFound().build());
    }
}