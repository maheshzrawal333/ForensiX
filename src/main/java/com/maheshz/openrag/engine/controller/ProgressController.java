package com.maheshz.openrag.engine.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@RestController
public class ProgressController {

    private static final Logger log = LoggerFactory.getLogger(ProgressController.class);

    // Maps Job ID to the active SSE Connection
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // SENIOR FIX: Replaced unbounded Map with a Time-To-Live (TTL) Caffeine Cache
    // Automatically evicts dead job logs after 2 hours to prevent OutOfMemory memory leaks.
    private final Cache<String, List<String>> messageCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.HOURS)
            .maximumSize(5000)
            .build();

    @GetMapping("/api/jobs/{jobId}/stream")
    public SseEmitter streamProgress(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(3600000L); // 1-hour timeout
        emitters.put(jobId, emitter);

        emitter.onCompletion(() -> cleanupConnection(jobId));
        emitter.onTimeout(() -> cleanupConnection(jobId));
        emitter.onError((e) -> cleanupConnection(jobId));

        List<String> cachedMessages = messageCache.getIfPresent(jobId);
        if (cachedMessages != null) {
            for (String msg : cachedMessages) {
                try {
                    emitter.send(SseEmitter.event().name("progress").data(msg));
                } catch (Exception e) {
                    log.warn("Failed to send cached progress to client for job {}", jobId);
                    cleanupConnection(jobId);
                    return emitter;
                }
            }
        }

        return emitter;
    }

    public void emitProgress(String jobId, String message) {
        // 1. Thread-safe compute inside Caffeine Cache
        List<String> logs = messageCache.get(jobId, k -> new CopyOnWriteArrayList<>());
        if (logs != null) {
            logs.add(message);
        }

        // 2. Send to live client
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(message));
            } catch (Exception e) {
                log.warn("Client disconnected during emit for job {}", jobId);
                emitters.remove(jobId);
            }
        }
    }

    public void completeEmitter(String jobId) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("progress").data("Complete"));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Failed to send Complete event for job {}", jobId);
            }
        }
        cleanupConnection(jobId);
        messageCache.invalidate(jobId); // Explicitly clear the cache since job is done
    }

    private void cleanupConnection(String jobId) {
        emitters.remove(jobId);
    }
}