package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.controller.dto.ChatRequest;
import com.maheshz.openrag.engine.controller.dto.ChatResponse;
import com.maheshz.openrag.engine.service.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping("/ask")
    public ResponseEntity<ChatResponse> askQuestion(@RequestBody ChatRequest request) {
        String answer = ragChatService.askQuestion(request);
        return ResponseEntity.ok(new ChatResponse(answer));
    }
}