package com.maheshz.ForensiX.engine.controller;

import com.maheshz.ForensiX.engine.domain.KnowledgeDocument;
import com.maheshz.ForensiX.engine.security.TenantContextHolder;
import com.maheshz.ForensiX.engine.service.EvidenceManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Enterprise API Boundary for Forensic Evidence Management.
 * <p>
 * This REST controller manages the lifecycle of physical evidence files (Documents) within
 * the ForensiX platform. It acts as the gateway between the frontend UI and the underlying
 * relational database and Vector/S3 storage services.
 * <p>
 * ARCHITECTURAL RULE: Every single endpoint in this controller MUST extract the active
 * `tenantId` from the ThreadLocal {@link TenantContextHolder} and pass it to the service layer.
 * This ensures that a malicious or accidental API call cannot interact with evidence
 * belonging to a different forensic case.
 */
@RestController
@RequestMapping("/api/knowledge/documents")
public class EvidenceController {

    private final EvidenceManagementService service;

    /**
     * Constructor injection ensures the service dependency is final and immutable.
     * @param service The core business logic service for evidence management.
     */
    public EvidenceController(EvidenceManagementService service) {
        this.service = service;
    }

    /**
     * Retrieves a scoped list of evidence files within a specific folder.
     *
     * @param folderId The UUID of the parent directory. (Use "root" for top-level files).
     * @return A 200 OK response containing the list of isolated documents.
     */
    @GetMapping
    public ResponseEntity<List<KnowledgeDocument>> listDocuments(@RequestParam String folderId) {
        // Enforce Tenant Boundary: Extract the Case ID verified by the TenantInterceptor
        String tenantId = TenantContextHolder.getTenantId();

        // Pass both the folder scoping and the tenant isolation key to the service
        return ResponseEntity.ok(service.getEvidenceForCase(folderId, tenantId));
    }

    /**
     * Completely purges a piece of evidence from the platform.
     * <p>
     * Note: This triggers a complex cascade in the Service layer, which must delete
     * the physical binary from S3/MinIO, wipe the semantic chunks from the pgvector database,
     * and delete the relational row from Postgres.
     *
     * @param id The UUID of the document to destroy.
     * @return A 204 No Content response upon successful deletion.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id) {
        // Enforce Tenant Boundary
        String tenantId = TenantContextHolder.getTenantId();

        service.deleteEvidence(id, tenantId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Updates the display name of a piece of evidence.
     * <p>
     * Uses PATCH instead of PUT because we are applying a partial update to a single field,
     * rather than replacing the entire KnowledgeDocument entity.
     *
     * @param id The UUID of the document to rename.
     * @param payload A Map expecting a single key: "fileName".
     * (Using a Map here avoids creating a single-field DTO for a simple operation).
     * @return A 200 OK response containing the updated document entity.
     */
    @PatchMapping("/{id}/rename")
    public ResponseEntity<KnowledgeDocument> renameDocument(@PathVariable String id, @RequestBody Map<String, String> payload) {
        // Enforce Tenant Boundary
        String tenantId = TenantContextHolder.getTenantId();

        String newName = payload.get("fileName");

        return ResponseEntity.ok(service.renameEvidence(id, tenantId, newName));
    }
}