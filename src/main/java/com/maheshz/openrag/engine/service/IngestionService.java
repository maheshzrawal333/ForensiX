package com.maheshz.openrag.engine.service;

import com.maheshz.openrag.engine.security.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingestText(String payloadText, String folderId) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("Ingesting document context into tenant: {}, folder: {}", currentTenant, folderId);

        // Simple sentence split chunker for quick structural testing
        // In production, you can substitute Spring AI's TokenTextSplitter here
        List<String> rawChunks = Arrays.asList(payloadText.split("(?<=\\.)\\s+"));

        List<Document> splitDocuments = rawChunks.stream()
                .filter(chunk -> !chunk.isBlank())
                .map(chunk -> {
                    Map<String, Object> metadata = Map.of(
                            "tenant_id", currentTenant,
                            "folder_id", folderId,
                            "chunk_id", UUID.randomUUID().toString()
                    );
                    return new Document(chunk, metadata);
                })
                .collect(Collectors.toList());

        log.info("Generated {} unique vector chunks. Pushing to PgVector...", splitDocuments.size());

        // This calculates the embedding arrays and saves them directly to your table
        vectorStore.accept(splitDocuments);
        log.info("Ingestion complete!");
    }
}