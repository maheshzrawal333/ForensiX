package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import com.maheshz.openrag.engine.repository.VectorCleanupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EvidenceManagementService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceManagementService.class);
    private final DocumentRepository documentRepository;
    private final VectorCleanupRepository vectorCleanupRepository;

    public EvidenceManagementService(DocumentRepository documentRepository, VectorCleanupRepository vectorCleanupRepository) {
        this.documentRepository = documentRepository;
        this.vectorCleanupRepository = vectorCleanupRepository;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocument> getEvidenceForCase(String folderId, String tenantId) {
        return documentRepository.findByFolderIdAndTenantId(folderId, tenantId);
    }

    @Transactional
    public void deleteEvidence(String documentId, String tenantId) {
        KnowledgeDocument doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found or access denied."));

        // SENIOR MANEUVER: Wipes AI memory via abstracted repository
        int deletedChunks = vectorCleanupRepository.wipeVectorsByDocumentId(documentId, tenantId);
        log.info("Wiped {} vector chunks from AI memory for document {}", deletedChunks, documentId);

        // Delete the relational tracker
        documentRepository.delete(doc);
    }

    @Transactional
    public KnowledgeDocument renameEvidence(String documentId, String tenantId, String newName) {
        KnowledgeDocument doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found or access denied."));

        doc.setFileName(newName);
        return documentRepository.save(doc);
    }
}