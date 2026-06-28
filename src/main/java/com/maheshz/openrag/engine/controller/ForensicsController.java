package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.controller.dto.ForensicsDTOs.*;
import com.maheshz.openrag.engine.service.RagChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forensics")
public class ForensicsController {

    private final RagChatService ragChatService;

    public ForensicsController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<ForensicAnalysisResponse> analyzeEvidence(@RequestBody ForensicAnalysisRequest request) {
        return ResponseEntity.ok(ragChatService.analyzeEvidence(request));
    }

    @PostMapping("/report")
    public ResponseEntity<ReportResponse> generateReport(@RequestBody ReportRequest request) {
        return ResponseEntity.ok(ragChatService.generateReport(request));
    }
}
