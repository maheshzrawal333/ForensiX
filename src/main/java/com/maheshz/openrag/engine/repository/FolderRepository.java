package com.maheshz.openrag.engine.repository;

import com.maheshz.openrag.engine.domain.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FolderRepository extends JpaRepository<Folder, String> {
    List<Folder> findByTenantIdAndParentFolderId(String tenantId, String parentFolderId);
}