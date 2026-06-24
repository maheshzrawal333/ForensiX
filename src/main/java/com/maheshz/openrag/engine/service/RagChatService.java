package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.controller.dto.ChatRequest;
import com.maheshz.openrag.engine.domain.TenantConfig;
import com.maheshz.openrag.engine.repository.TenantConfigRepository;
import com.maheshz.openrag.engine.security.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final TenantConfigRepository tenantConfigRepository;

    public RagChatService(ChatModel chatModel, VectorStore vectorStore, TenantConfigRepository tenantConfigRepository) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
        this.tenantConfigRepository = tenantConfigRepository;
    }

    public String askQuestion(ChatRequest request) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("Processing chat request for tenant {} in folder {}", currentTenant, request.folderId());

        TenantConfig config = tenantConfigRepository.findById(currentTenant)
                .orElseGet(() -> {
                    TenantConfig defaultConfig = new TenantConfig();
                    defaultConfig.setTenantId(currentTenant);
                    defaultConfig.setAiModelName("llama3.2:3b"); // Safe fallback defaults
                    defaultConfig.setSystemPrompt("You are a helpful assistant.");
                    return defaultConfig;
                });

        // Instantiate the builder
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        Filter.Expression filter = b.and(
                b.eq("tenant_id", currentTenant),
                b.eq("folder_id", request.folderId())
        ).build();

        SearchRequest searchRequest = SearchRequest.defaults()
                .withFilterExpression(filter)
                .withTopK(3);

        // Build the dynamic client context
        return ChatClient.create(chatModel).prompt()
                .user(request.question())
                .system(config.getSystemPrompt())
                .options(ChatOptionsBuilder.builder()
                        .withTemperature(config.getTemperature())
                        .withModel(config.getAiModelName())
                        .build())
                .advisors(new QuestionAnswerAdvisor(vectorStore, searchRequest))
                .call()
                .content();
    }
}