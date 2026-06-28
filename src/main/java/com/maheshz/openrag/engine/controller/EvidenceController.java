package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.service.EvidenceManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/documents")
public class EvidenceController {

    private final EvidenceManagementService service;

    public EvidenceController(EvidenceManagementService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeDocument>> listDocuments(
            @RequestParam String folderId,
            @RequestHeader("X-Tenant-ID") String tenantId) {
        return ResponseEntity.ok(service.getEvidenceForCase(folderId, tenantId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId) {
        service.deleteEvidence(id, tenantId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/rename")
    public ResponseEntity<KnowledgeDocument> renameDocument(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestBody Map<String, String> payload) {
        String newName = payload.get("fileName");
        return ResponseEntity.ok(service.renameEvidence(id, tenantId, newName));
    }
}
