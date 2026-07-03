package com.maheshz.ForensiX.engine.controller;

import com.maheshz.ForensiX.engine.controller.dto.ForensicsDTOs.*;
import com.maheshz.ForensiX.engine.service.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Enterprise AI Inference and Reporting Boundary.
 * <p>
 * This REST controller serves as the primary execution gateway for all Large Language Model (LLM)
 * operations within the ForensiX platform. It delegates incoming HTTP requests to the
 * underlying RagChatService, which handles the complex orchestration of vector retrieval,
 * prompt engineering, and Ollama API communication.
 * <p>
 * ARCHITECTURAL NOTE: These endpoints orchestrate synchronous, heavily compute-bound tasks.
 * The client UI must maintain long-lived HTTP connections (with extended timeouts configured
 * in our WebConfig and api.js) to allow the local models sufficient time to generate responses.
 */
@RestController
public class ForensicsController {

    private final RagChatService ragChatService;

    /**
     * Constructor injection ensures the AI service dependency is final, immutable,
     * and easily mockable during unit testing.
     */
    public ForensicsController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /**
     * Triggers the core Retrieval-Augmented Generation (RAG) pipeline.
     * <p>
     * This endpoint receives an investigator's question, triggers a hybrid semantic search
     * against the pgvector database (scoped by the user's active Case ID), and feeds the
     * resulting evidence chunks into the selected LLM for analysis.
     *
     * @param request The validated DTO containing the question, folder scope, and chosen model.
     * @return HTTP 200 OK with the structured JSON analysis and evidentiary citations.
     */
    @PostMapping("/api/forensics/analyze")
    public ResponseEntity<ForensicAnalysisResponse> analyzeEvidence(@RequestBody ForensicAnalysisRequest request) {
        // Validation and tenant-scoping are handled by the DTO and TenantInterceptor upstream.
        // We pass the clean payload directly to the service layer.
        return ResponseEntity.ok(ragChatService.analyzeEvidence(request));
    }

    /**
     * Synthesizes the final chronological case report.
     * <p>
     * CHAIN OF CUSTODY ENFORCEMENT: Unlike the `analyze` endpoint which actively searches the
     * database, this endpoint accepts a strict array of investigator-validated facts.
     * By forcing the LLM to write a report *only* using these human-verified strings,
     * we mathematically eliminate the risk of the AI hallucinating new evidence into the final legal document.
     *
     * @param request The validated DTO containing the array of verified facts.
     * @return HTTP 200 OK with the generated Markdown/Text narrative.
     */
    @PostMapping("/api/forensics/report")
    public ResponseEntity<ReportResponse> generateReport(@RequestBody ReportRequest request) {
        return ResponseEntity.ok(ragChatService.generateReport(request));
    }

    /**
     * Retrieves the whitelist of authorized, pre-provisioned LLMs.
     * <p>
     * ZERO-TRUST DESIGN EXPLANATION:
     * In a standard microservice, we might use a WebClient to hit `http://localhost:11434/api/tags`
     * and parse the dynamic Ollama inventory. However, ForensiX is designed for secure, air-gapped
     * forensic architecture.
     * <p>
     * By explicitly declaring our validated models here, we:
     * 1. Prevent API abuse (users cannot request arbitrary models installed on the host machine).
     * 2. Guarantee deterministic behavior (we only expose models we have rigorously prompt-engineered against).
     * 3. Mask underlying infrastructure details from the frontend.
     *
     * @return HTTP 200 OK with the static list of approved AI models.
     */
    @GetMapping("/api/admin/models")
    public ResponseEntity<List<String>> getAvailableModels() {
        return ResponseEntity.ok(List.of(
                "llama3.2:3b",
                "qwen2.5-coder:7b",
                "deepseek-r1:8b",
                "deepseek-coder-v2:16b"
        ));
    }
}