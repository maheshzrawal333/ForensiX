package com.maheshz.ForensiX.engine.repository;

import com.maheshz.ForensiX.engine.domain.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Enterprise Data Access Layer for Hierarchical Case Organization.
 * <p>
 * This repository manages the persistence of the virtual directory structures.
 * In the ForensiX RAG architecture, these folders act as physical boundaries for vector
 * similarity searches.
 * <p>
 * ARCHITECTURAL RULE: Because the Folder entity implements the Adjacency List pattern
 * (where each row points to its parent), querying this repository must be done strictly
 * one level at a time (lazy-loading) to prevent recursive N+1 query blowouts when a
 * forensic case scales to thousands of nested sub-directories.
 */
@Repository
public interface FolderRepository extends JpaRepository<Folder, String> {

    /**
     * Retrieves the immediate children of a specific directory within a secured case boundary.
     * <p>
     * SECURITY BOUNDARY (IDOR PREVENTION):
     * We explicitly mandate the {@code tenantId} parameter in this query. Even if an attacker
     * guesses the UUID of a {@code parentFolderId} belonging to another investigation,
     * the database will return an empty list because the {@code tenantId} predicate will fail.
     * <p>
     * PERFORMANCE NOTE:
     * This method fetches exactly one depth level of the hierarchy. If {@code parentFolderId}
     * is null, it returns the top-level "Root" folders for the case.
     *
     * @param tenantId The validated investigative case ID (X-Tenant-ID).
     * @param parentFolderId The UUID of the parent directory (can be null for root level).
     * @return A list of strictly isolated, immediate sub-folders.
     */
    List<Folder> findByTenantIdAndParentFolderId(String tenantId, String parentFolderId);
}