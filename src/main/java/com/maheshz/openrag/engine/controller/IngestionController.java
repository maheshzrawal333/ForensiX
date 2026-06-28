package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.controller.dto.UploadResponse;
import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import com.maheshz.openrag.engine.security.TenantContextHolder;
import com.maheshz.openrag.engine.service.IngestionOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
@Tag(name = "1. Evidence Ingestion", description = "Endpoints for safely uploading and vectorizing raw forensic files.")
public class IngestionController {

    private final IngestionOrchestrator ingestionOrchestrator;
    private final DocumentRepository documentRepository;

    public IngestionController(IngestionOrchestrator ingestionOrchestrator, DocumentRepository documentRepository) {
        this.ingestionOrchestrator = ingestionOrchestrator;
        this.documentRepository = documentRepository;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload Raw Evidence", description = "Accepts .txt, .pdf, or .docx files. Executes Tika extraction and Vector embedding asynchronously.")
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("folderId") String folderId) {

        String tenantId = TenantContextHolder.getTenantId();
        String jobId = ingestionOrchestrator.initiateFileUpload(file, tenantId, folderId);

        return ResponseEntity.accepted().body(new UploadResponse(jobId));
    }

}