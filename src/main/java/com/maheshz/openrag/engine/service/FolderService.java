package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.domain.Folder;
import com.maheshz.openrag.engine.repository.DocumentRepository;
import com.maheshz.openrag.engine.repository.FolderRepository;
import com.maheshz.openrag.engine.security.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;

    public FolderService(FolderRepository folderRepository, DocumentRepository documentRepository) {
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public Folder createFolder(String name, String parentFolderId) {
        String tenantId = getValidatedTenantId();

        Folder folder = new Folder();
        folder.setId(UUID.randomUUID().toString());
        folder.setTenantId(tenantId);
        folder.setName(name);
        folder.setParentFolderId(parentFolderId);

        return folderRepository.save(folder);
    }

    // SENIOR OPTIMIZATION: readOnly = true improves database performance for fetch queries
    @Transactional(readOnly = true)
    public List<Folder> getFolders(String parentFolderId) {
        String tenantId = getValidatedTenantId();
        return folderRepository.findByTenantIdAndParentFolderId(tenantId, parentFolderId);
    }

    @Transactional
    public Folder renameFolder(String folderId, String newName) {
        String tenantId = getValidatedTenantId();

        Folder folder = folderRepository.findById(folderId)
                .filter(f -> f.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found or access denied."));

        folder.setName(newName);
        return folderRepository.save(folder);
    }

    // --- UPGRADED: Recursive Cascading Delete ---
    @Transactional
    public void deleteFolder(String folderId) {
        String tenantId = getValidatedTenantId();

        // 1. Verify the target folder exists and belongs to the active case
        Folder targetFolder = folderRepository.findById(folderId)
                .filter(f -> f.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Folder not found or access denied."));

        // 2. Trigger the recursive wipe (bottom-up deletion)
        deleteFolderContentsRecursively(folderId, tenantId);

        // 3. Finally, delete the targeted parent folder itself
        folderRepository.delete(targetFolder);
    }

    /**
     * Helper method that digs into the folder tree, deleting all files and nested folders from the bottom up.
     */
    private void deleteFolderContentsRecursively(String parentFolderId, String tenantId) {
        // Step A: Find and wipe all files sitting directly inside this specific folder
        documentRepository.findByFolderIdAndTenantId(parentFolderId, tenantId)
                .forEach(documentRepository::delete);

        // (Note: If you eventually add VectorStore deletion here, you would collect the document IDs
        //  from the query above and pass them to vectorStore.delete(idList) to wipe AI memory).

        // Step B: Find all sub-folders inside this folder
        List<Folder> subFolders = folderRepository.findByTenantIdAndParentFolderId(tenantId, parentFolderId);

        // Step C: For every sub-folder found, dig deeper (Recursion), then delete the empty shell
        for (Folder subFolder : subFolders) {
            deleteFolderContentsRecursively(subFolder.getId(), tenantId); // Dig deeper
            folderRepository.delete(subFolder); // Delete the empty folder shell
        }
    }

    /**
     * SENIOR GUARDRAIL: Centralized Tenant Validation.
     * Prevents NullPointerExceptions and database integrity violations if a request
     * slips through without an active Case ID.
     */
    private String getValidatedTenantId() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalStateException("CRITICAL: Operation denied. No active Case ID (Tenant) found in context.");
        }
        return tenantId;
    }
}