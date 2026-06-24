package com.maheshz.openrag.engine.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/jobs")
public class ProgressController {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 minute timeout
        emitters.put(jobId, emitter);

        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(e -> emitters.remove(jobId));

        return emitter;
    }

    public void emitProgress(String jobId, String message) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(message));
            } catch (IOException e) {
                emitters.remove(jobId);
            }
        }
    }

    public void completeEmitter(String jobId) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            emitter.complete();
            emitters.remove(jobId);
        }
    }
}