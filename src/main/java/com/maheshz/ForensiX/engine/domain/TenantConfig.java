package com.maheshz.ForensiX.engine.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class TenantConfig {

    @Id
    private String tenantId;
    private String aiModelName = "llama3.2:3b";
    private Double temperature = 0.0;
    private String systemPrompt = "You are a helpful assistant.";

    // Getters and Setters
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAiModelName() { return aiModelName; }
    public void setAiModelName(String aiModelName) { this.aiModelName = aiModelName; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}