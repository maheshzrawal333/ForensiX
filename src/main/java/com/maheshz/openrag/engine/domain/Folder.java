package com.maheshz.openrag.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Folder extends BaseTenantEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String parentFolderId;

    @Column(nullable = false)
    private String name;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getParentFolderId() { return parentFolderId; }
    public void setParentFolderId(String parentFolderId) { this.parentFolderId = parentFolderId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}