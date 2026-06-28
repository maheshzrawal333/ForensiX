package com.maheshz.openrag.engine.controller.dto;

import java.util.List;

public class ForensicsDTOs {

    // Request from Frontend to analyze specific folders
    public record ForensicAnalysisRequest(String question, List<String> folderIds, String model) {}

    // Structured JSON Response forced out of the LLM
    public record ForensicAnalysisResponse(String answer, String reasoning) {}

    // Request from Frontend to synthesize verified facts
    public record ReportRequest(List<String> evidence, String model) {}

    // Response containing the final narrative
    public record ReportResponse(String report) {}
}