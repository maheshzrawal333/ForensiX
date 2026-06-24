package com.maheshz.openrag.engine.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Folder {

    @Id
    private String id;
    private String tenantId;
    private String parentFolderId;
    private String name;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getParentFolderId() { return parentFolderId; }
    public void setParentFolderId(String parentFolderId) { this.parentFolderId = parentFolderId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}