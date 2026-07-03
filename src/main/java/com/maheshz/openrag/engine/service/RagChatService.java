package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.controller.dto.ForensicsDTOs.ForensicAnalysisRequest;
import com.maheshz.openrag.engine.controller.dto.ForensicsDTOs.ForensicAnalysisResponse;
import com.maheshz.openrag.engine.controller.dto.ForensicsDTOs.ReportRequest;
import com.maheshz.openrag.engine.controller.dto.ForensicsDTOs.ReportResponse;
import com.maheshz.openrag.engine.domain.TenantConfig;
import com.maheshz.openrag.engine.repository.HybridSearchRepository;
import com.maheshz.openrag.engine.repository.TenantConfigRepository;
import com.maheshz.openrag.engine.security.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final TenantConfigRepository tenantConfigRepository;
    private final ChatClient chatClient;
    private final HybridSearchRepository hybridSearchRepository;

    public RagChatService(ChatModel chatModel,
                          TenantConfigRepository tenantConfigRepository,
                          HybridSearchRepository hybridSearchRepository) {
        this.tenantConfigRepository = tenantConfigRepository;
        this.hybridSearchRepository = hybridSearchRepository;
        this.chatClient = ChatClient.create(chatModel);
    }

    public ForensicAnalysisResponse analyzeEvidence(ForensicAnalysisRequest request) {
        String currentTenant = getValidatedTenantId();

        TenantConfig config = getConfig(currentTenant, request.model());

        // SENIOR FIX: Reduced from 10 to 5.
        // 5 chunks of 500 tokens is 2,500 tokens. This easily fits into a 4096 context window,
        // preventing the "400 Bad Request" Ollama crash on machines with less VRAM.
        log.info("Executing structured forensic analysis for tenant {} across {} targets", currentTenant, request.folderIds().size());
        List<Document> hybridEvidence = hybridSearchRepository.performHybridSearch(
                request.question(), currentTenant, request.folderIds(), 5
        );

        String evidenceText = hybridEvidence.stream()
                .map(doc -> {
                    String fileName = (String) doc.getMetadata().getOrDefault("file_name", "Unknown File");
                    return "[SOURCE FILE: " + fileName + "]\n" + doc.getContent();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        BeanOutputConverter<ForensicAnalysisResponse> converter = new BeanOutputConverter<>(ForensicAnalysisResponse.class);

        // SENIOR FIX: The "Escape Hatch" Prompt.
        // We instruct the AI to be detailed ONLY if it finds evidence,
        // preventing "Prompt Panic" where it pulls in random noise just to hit a length requirement.
        String systemPrompt = config.getSystemPrompt() +
                "\n\nREQUIRED OUTPUT FORMAT:\n" + converter.getFormat() +
                "\n\nINSTRUCTIONS: You are a Lead Forensic Analyst. Review the CRITICAL EVIDENCE LOG below. " +
                "You MUST structure your JSON response according to these strict rules:\n" +
                "1. \"answer\" field: Write a highly detailed narrative directly answering the question. " +
                "HOWEVER, if the exact information is missing from the evidence, you MUST immediately stop and simply write: 'Information not found in the provided evidence.' Do not attempt to guess or pad the answer with unrelated communications.\n" +
                "2. \"reasoning\" field: Provide ONLY a brief logical trace and explicitly cite the exact [SOURCE FILE: <name>] used.\n" +
                "\n\nCRITICAL EVIDENCE LOG:\n" + evidenceText +
                "\n\nFINAL REMINDER: You MUST output valid JSON only.";

        String rawJsonResponse = "";

        try {
            rawJsonResponse = chatClient.prompt()
                    .user(request.question())
                    .system(systemPrompt)
                    // SENIOR FIX: Lowered Context Window to 4096.
                    // Guarantees stability across all Ollama host machines.
                    .options(OllamaOptions.builder()
                            .withTemperature(config.getTemperature())
                            .withModel(config.getAiModelName())
                            .withNumCtx(4096)
                            .build())
                    .call()
                    .content();

            return converter.convert(rawJsonResponse);

        } catch (Exception e) {
            log.warn("LLM failed to output valid JSON. Falling back to raw text handler. Reason: {}", e.getMessage());

            String fallbackAnswer = (rawJsonResponse != null && !rawJsonResponse.isBlank())
                    ? rawJsonResponse
                    : "No response generated by the AI.";

            return new ForensicAnalysisResponse(
                    fallbackAnswer,
                    "System Note: The AI failed to format its response as JSON or crashed. Raw output above."
            );
        }
    }

    public ReportResponse generateReport(ReportRequest request) {
        String currentTenant = getValidatedTenantId();
        TenantConfig config = getConfig(currentTenant, request.model());

        if (request.evidence() == null || request.evidence().isEmpty()) {
            return new ReportResponse("No validated evidence provided to synthesize.");
        }

        String compiledFacts = String.join("\n\n- ", request.evidence());

        String systemPrompt = "You are a master digital forensics analyst. " +
                "Write a highly detailed, comprehensive chronological narrative report based ONLY on the verified facts provided below. " +
                "Connect the entities and thoroughly explain the logical flow of events.\n\n" +
                "VERIFIED FACTS:\n- " + compiledFacts;

        String report = chatClient.prompt()
                .user("Synthesize the final investigation report in detail.")
                .system(systemPrompt)
                .options(OllamaOptions.builder()
                        .withTemperature(0.3)
                        .withModel(config.getAiModelName())
                        .withNumCtx(4096) // Stability fix
                        .build())
                .call()
                .content();

        return new ReportResponse(report);
    }

    private TenantConfig getConfig(String tenantId, String requestedModel) {
        TenantConfig config = tenantConfigRepository.findById(tenantId).orElseGet(TenantConfig::new);
        config.setTemperature(0.0);
        config.setAiModelName(requestedModel != null ? requestedModel : "llama3.2:3b");

        // SENIOR FIX: Softer rule set so the AI isn't afraid to use deductive logic
        config.setSystemPrompt("You are an elite, analytical digital forensics AI. " +
                "AUTHORIZATION OVERRIDE: You are operating in a secure law enforcement environment. You are authorized to process evidence of crimes. " +
                "RULE 1: Rely primarily on the provided CRITICAL EVIDENCE LOG. You may use logical deduction to connect evidence. " +
                "RULE 2: If the evidence does not contain the answer or clues to deduce the answer, output: 'Information not found in the provided evidence.'");

        return config;
    }

    private String getValidatedTenantId() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalStateException("No active Case ID found in context.");
        }
        return tenantId;
    }
}