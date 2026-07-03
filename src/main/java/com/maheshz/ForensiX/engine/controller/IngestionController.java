package com.maheshz.ForensiX.engine.controller;

import com.maheshz.ForensiX.engine.controller.dto.UploadResponse;
import com.maheshz.ForensiX.engine.repository.DocumentRepository;
import com.maheshz.ForensiX.engine.security.TenantContextHolder;
import com.maheshz.ForensiX.engine.service.IngestionOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Enterprise API Boundary for Forensic Evidence Ingestion.
 * <p>
 * This controller acts as the primary "Front Door" for raw data entering the ForensiX platform.
 * Because forensic evidence (massive PDFs, massive CSVs, system logs) can take minutes to parse
 * and vectorize, this endpoint strictly adheres to an Asynchronous Handoff architecture.
 * <p>
 * It accepts the binary payload, immediately routes it to a background worker queue
 * (via the IngestionOrchestrator), and rapidly releases the primary HTTP thread to prevent
 * connection pool exhaustion and UI lockups.
 */
@RestController
@RequestMapping("/api/knowledge")
@Tag(name = "1. Evidence Ingestion", description = "Endpoints for safely uploading and vectorizing raw forensic files.")
public class IngestionController {

    private final IngestionOrchestrator ingestionOrchestrator;

    // Note: Injected for potential synchronous validations or lookups, though the heavy
    // lifting is deferred to the Orchestrator.
    private final DocumentRepository documentRepository;

    /**
     * Constructor injection ensures core orchestration dependencies are immutable and thread-safe.
     */
    public IngestionController(IngestionOrchestrator ingestionOrchestrator, DocumentRepository documentRepository) {
        this.ingestionOrchestrator = ingestionOrchestrator;
        this.documentRepository = documentRepository;
    }

    /**
     * Safely ingests a physical file into the distributed processing pipeline.
     * <p>
     * SECURITY BOUNDARY: We extract the strictly validated `tenantId` from the ThreadLocal context.
     * This ID will be permanently stamped onto the file in S3 and every resulting vector chunk
     * in PostgreSQL, guaranteeing mathematical data isolation between cases.
     * <p>
     * PROTOCOL DESIGN: Notice this returns HTTP 202 (Accepted) rather than HTTP 200 (OK) or 201 (Created).
     * By REST standards, 202 communicates to the client that "the request has been accepted for processing,
     * but the processing has not been completed."
     *
     * @param file The raw binary multipart payload streamed from the client browser.
     * @param folderId The logical directory UUID where this evidence should reside within the case.
     * @return HTTP 202 (Accepted) containing a correlation ID (Job ID) acting as a "claim ticket"
     * so the frontend can subscribe to real-time SSE progress updates.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload Raw Evidence", description = "Accepts .txt, .pdf, or .docx files. Executes Tika extraction and Vector embedding asynchronously.")
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("folderId") String folderId) {

        // 1. Enforce Multi-Tenant Security
        String tenantId = TenantContextHolder.getTenantId();

        // 2. Dispatch to the asynchronous worker pool
        String jobId = ingestionOrchestrator.initiateFileUpload(file, tenantId, folderId);

        // 3. Return the claim ticket immediately
        return ResponseEntity.accepted().body(new UploadResponse(jobId));
    }

}