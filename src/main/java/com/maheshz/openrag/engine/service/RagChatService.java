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
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
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

    /**
     * STRUCTURED ANALYSIS PIPELINE
     */
    public ForensicAnalysisResponse analyzeEvidence(ForensicAnalysisRequest request) {
        String currentTenant = getValidatedTenantId();
        log.info("Executing structured forensic analysis for tenant {} across {} targets", currentTenant, request.folderIds().size());

        TenantConfig config = getConfig(currentTenant, request.model());

        // 1. EXECUTE MULTI-TARGET HYBRID SEARCH
        List<Document> hybridEvidence = hybridSearchRepository.performHybridSearch(
                request.question(), currentTenant, request.folderIds(), 15
        );

        // 2. INJECT METADATA FOR CITATIONS
        String evidenceText = hybridEvidence.stream()
                .map(doc -> {
                    String fileName = (String) doc.getMetadata().getOrDefault("file_name", "Unknown File");
                    return "[SOURCE FILE: " + fileName + "]\n" + doc.getContent();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        // 3. INITIALIZE STRUCTURED OUTPUT CONVERTER
        BeanOutputConverter<ForensicAnalysisResponse> converter = new BeanOutputConverter<>(ForensicAnalysisResponse.class);

        // 4. BUILD STRICT SYSTEM PROMPT
        String systemPrompt = config.getSystemPrompt() +
                "\n\nYou must analyze the evidence below. Provide a direct 'answer' to the user's query, " +
                "and separately provide detailed 'reasoning' that explicitly cites the SOURCE FILE names used to reach your conclusion." +
                "\n\nCRITICAL EVIDENCE LOG:\n" + evidenceText +
                "\n\n" + converter.getFormat();

        try {
            // 5. EXECUTE SYNCHRONOUS CALL
            String rawJsonResponse = chatClient.prompt()
                    .user(request.question())
                    .system(systemPrompt)
                    .options(ChatOptionsBuilder.builder()
                            .withTemperature(config.getTemperature())
                            .withModel(config.getAiModelName())
                            .build())
                    .call()
                    .content();

            // 6. PARSE AND RETURN
            return converter.convert(rawJsonResponse);

        } catch (Exception e) {
            log.error("LLM failed to output valid JSON schema for Structured Output.", e);
            // SENIOR FIX: Graceful fallback so the frontend doesn't crash completely.
            return new ForensicAnalysisResponse(
                    "Analysis completed, but the AI failed to format the response properly.",
                    "System Error: The LLM hallucinated outside the strict JSON schema. Please retry the query."
            );
        }
    }

    /**
     * NARRATIVE SYNTHESIS PIPELINE
     */
    public ReportResponse generateReport(ReportRequest request) {
        String currentTenant = getValidatedTenantId();
        TenantConfig config = getConfig(currentTenant, request.model());

        if (request.evidence() == null || request.evidence().isEmpty()) {
            return new ReportResponse("No validated evidence provided to synthesize.");
        }

        String compiledFacts = String.join("\n\n- ", request.evidence());

        String systemPrompt = "You are a master digital forensics analyst. " +
                "Write a comprehensive, chronological narrative report of the investigation based ONLY on the verified facts provided below. " +
                "Do not hallucinate external details. Connect the entities and explain the logical flow of events.\n\n" +
                "VERIFIED FACTS:\n- " + compiledFacts;

        String report = chatClient.prompt()
                .user("Synthesize the final investigation report.")
                .system(systemPrompt)
                .options(ChatOptionsBuilder.builder()
                        .withTemperature(0.3)
                        .withModel(config.getAiModelName())
                        .build())
                .call()
                .content();

        return new ReportResponse(report);
    }

    private TenantConfig getConfig(String tenantId, String requestedModel) {
        TenantConfig config = tenantConfigRepository.findById(tenantId).orElseGet(TenantConfig::new);
        config.setTemperature(0.0); // Keep temperature zero for factual accuracy
        config.setAiModelName(requestedModel != null ? requestedModel : "llama3.2:3b");
        config.setSystemPrompt("You are a strict, expert digital forensics AI. " +
                "If the answer is not in the context, state 'Information not found in the provided evidence.' Do not guess.");
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