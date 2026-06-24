package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.domain.Folder;
import com.maheshz.openrag.engine.repository.FolderRepository;
import com.maheshz.openrag.engine.security.TenantContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class FolderService {

    private final FolderRepository folderRepository;

    public FolderService(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    public Folder createFolder(String name, String parentFolderId) {
        String tenantId = TenantContextHolder.getTenantId();

        Folder folder = new Folder();
        folder.setId(UUID.randomUUID().toString());
        folder.setTenantId(tenantId);
        folder.setName(name);
        folder.setParentFolderId(parentFolderId);

        return folderRepository.save(folder);
    }

    public List<Folder> getFolders(String parentFolderId) {
        String tenantId = TenantContextHolder.getTenantId();
        return folderRepository.findByTenantIdAndParentFolderId(tenantId, parentFolderId);
    }
}