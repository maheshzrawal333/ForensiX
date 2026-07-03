package com.maheshz.ForensiX.engine.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Enterprise AI Inference Configuration Registry.
 * <p>
 * This entity acts as a 1-to-1 extension of the {@link Tenant} (Investigative Case) aggregate root.
 * Rather than hardcoding LLM parameters globally, this table allows investigators to tailor
 * the AI's behavior, model selection, and bounding prompts strictly to the context of a specific case.
 * <p>
 * ARCHITECTURAL NOTE:
 * We separate this from the main {@code Tenant} table to avoid bloating the core case metadata
 * with AI-specific hyperparameters, adhering to the Single Responsibility Principle (SRP).
 */
@Entity
public class TenantConfig {

    /**
     * The Shared Primary Key.
     * <p>
     * This field acts as both the Primary Key for this table and the implicit Foreign Key
     * pointing back to the {@code Tenant} table. This enforces a strict 1:1 relationship
     * at the schema level, ensuring a case can never have conflicting AI configurations.
     */
    @Id
    private String tenantId;

    /**
     * The designated local LLM for this specific case.
     * <p>
     * Defaults to "llama3.2:3b", ensuring that if a case is instantiated without explicitly
     * defining a model, the system safely falls back to a fast, low-VRAM local model rather
     * than throwing a NullPointerException during the first RAG query.
     */
    private String aiModelName = "llama3.2:3b";

    /**
     * Hyperparameter: Inference Determinism (Creativity vs. Factuality).
     * <p>
     * FORENSIC GUARDRAIL: This defaults to `0.0` (Greedy Decoding) and should rarely, if ever,
     * be increased in a production forensic environment.
     * In legal tech, AI hallucination is unacceptable. A temperature of 0.0 mathematically
     * forces the LLM to select the most probable next token, ensuring highly deterministic,
     * repeatable, and strictly factual answers based ONLY on the provided context vectors.
     */
    private Double temperature = 0.0;

    /**
     * The foundational System Prompt (Persona and Guardrails).
     * <p>
     * This string is prepended to every single query made within the case. It establishes
     * the AI's role and rules of engagement (e.g., "You are a forensic analyst. Only answer
     * using the provided context. If you do not know, say 'Insufficient Evidence'.").
     */
    private String systemPrompt = "You are a helpful assistant.";

    // ==========================================
    // GETTERS & SETTERS
    // ==========================================
    // Standard mutators required by the Hibernate ORM for entity hydration.

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getAiModelName() {
        return aiModelName;
    }

    public void setAiModelName(String aiModelName) {
        this.aiModelName = aiModelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}