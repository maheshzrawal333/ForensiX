package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.domain.Folder;
import com.maheshz.openrag.engine.service.FolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    public ResponseEntity<Folder> createFolder(@RequestParam String name, @RequestParam(required = false) String parentFolderId) {
        return ResponseEntity.ok(folderService.createFolder(name, parentFolderId));
    }

    @GetMapping
    public ResponseEntity<List<Folder>> getFolders(@RequestParam(required = false) String parentFolderId) {
        return ResponseEntity.ok(folderService.getFolders(parentFolderId));
    }

    // --- NEW ENDPOINTS ---
    @PatchMapping("/{id}/rename")
    public ResponseEntity<Folder> renameFolder(@PathVariable String id, @RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(folderService.renameFolder(id, payload.get("name")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFolder(@PathVariable String id) {
        folderService.deleteFolder(id);
        return ResponseEntity.noContent().build();
    }
}