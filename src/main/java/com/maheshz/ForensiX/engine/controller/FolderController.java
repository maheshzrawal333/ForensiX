package com.maheshz.ForensiX.engine.controller;

import com.maheshz.ForensiX.engine.domain.Folder;
import com.maheshz.ForensiX.engine.service.FolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Enterprise API Boundary for Hierarchical Case Organization.
 * <p>
 * This REST controller manages the virtual directory structure for forensic cases.
 * Architecturally, folders are not just UI components; they act as "Vector Search Boundaries."
 * By placing evidence in specific folders, investigators can scope the LLM's context window
 * strictly to that directory, significantly increasing RAG accuracy and reducing token noise.
 */
@RestController
@RequestMapping("/api/folders")
@Tag(name = "2. Folder Management", description = "Endpoints for managing the hierarchical case folder structure")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    /**
     * Provisions a new virtual directory.
     * <p>
     * Uses POST because this is a non-idempotent operation (calling it multiple times
     * without an idempotency key would theoretically create duplicate folders).
     *
     * @param request The validated DTO containing the folder name and parent correlation ID.
     * @return HTTP 201 (Created) with the finalized entity state (including its generated UUID).
     */
    @PostMapping
    @Operation(summary = "Create Folder", description = "Creates a new folder within the specified parent directory.")
    public ResponseEntity<Folder> createFolder(@Valid @RequestBody CreateFolderRequest request) {
        // SENIOR FIX: Returning HTTP 201 instead of HTTP 200 explicitly informs the
        // client that a new server-side resource was successfully instantiated.
        Folder createdFolder = folderService.createFolder(request.name(), request.parentFolderId());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdFolder);
    }

    /**
     * Retrieves the children of a specific directory level.
     * <p>
     * Note: We use lazy-loading (requesting by parentFolderId) rather than returning the entire
     * nested tree at once. This prevents massive JSON payloads and DB strain when cases scale
     * to thousands of nested folders.
     *
     * @param parentFolderId The UUID of the parent, or null/"root" for the top-level case directory.
     * @return HTTP 200 OK with the flat list of immediate sub-folders.
     */
    @GetMapping
    @Operation(summary = "List Folders", description = "Retrieves all sub-folders for a given parent directory.")
    public ResponseEntity<List<Folder>> getFolders(@RequestParam(required = false) String parentFolderId) {
        return ResponseEntity.ok(folderService.getFolders(parentFolderId));
    }

    /**
     * Performs a partial update to mutate the folder's display name.
     * <p>
     * Uses PATCH instead of PUT because we are explicitly modifying only a subset of the
     * resource's fields (the name), rather than replacing the entire Folder entity wholesale.
     *
     * @param id The UUID of the target folder.
     * @param request The validated DTO containing the new name.
     * @return HTTP 200 OK with the updated folder entity.
     */
    @PatchMapping("/{id}/rename")
    @Operation(summary = "Rename Folder", description = "Updates the name of an existing folder.")
    public ResponseEntity<Folder> renameFolder(@PathVariable String id, @Valid @RequestBody RenameFolderRequest request) {
        return ResponseEntity.ok(folderService.renameFolder(id, request.name()));
    }

    /**
     * Irreversibly destroys a folder and its entire structural lineage.
     * <p>
     * WARNING: This endpoint triggers an aggressive cascading deletion. The underlying service
     * must transactionally wipe the folder, all child folders, all associated Evidence (S3 binaries),
     * and all pgvector embeddings to prevent orphan data and ghost vectors.
     *
     * @param id The UUID of the folder to obliterate.
     * @return HTTP 204 (No Content) indicating successful deletion without returning a body.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete Folder", description = "Recursively deletes a folder, its sub-folders, and wipes associated AI vectors.")
    public ResponseEntity<Void> deleteFolder(@PathVariable String id) {
        folderService.deleteFolder(id);
        return ResponseEntity.noContent().build();
    }

    // ==========================================
    // DATA TRANSFER OBJECTS (DTOs)
    // ==========================================
    // These internal records act as our perimeter defense. By enforcing JSR-380 validation
    // constraints here, we prevent malicious or malformed data from ever reaching the Service layer.

    /**
     * Encapsulates the payload for creating new directories.
     */
    public record CreateFolderRequest(
            @NotBlank(message = "Folder name cannot be blank")
            @Size(max = 100, message = "Folder name must be under 100 characters")
            // ^ Prevents DB truncation errors and protects the frontend UI from rendering overflowing strings.
            String name,

            String parentFolderId
    ) {}

    /**
     * Encapsulates the payload for folder renaming operations.
     */
    public record RenameFolderRequest(
            @NotBlank(message = "New folder name cannot be blank")
            @Size(max = 100, message = "Folder name must be under 100 characters")
            String name
    ) {}
}