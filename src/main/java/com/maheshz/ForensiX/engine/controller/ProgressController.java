package com.maheshz.ForensiX.engine.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.maheshz.ForensiX.engine.config.RedisPubSubConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Distributed Real-Time Telemetry Gateway.
 * <p>
 * This controller manages Server-Sent Events (SSE) to stream live progress from background
 * AI and parsing workers to the frontend UI.
 * <p>
 * ARCHITECTURAL DESIGN (Redis Pub/Sub):
 * In a multi-node environment, the server processing a heavy file might not be the same
 * server holding the client's SSE HTTP connection. To solve this, workers do not emit
 * directly to local memory; they broadcast progress to a Redis Channel. This controller
 * dynamically subscribes to those channels, bridging the Redis cluster network to the
 * client's HTTP network.
 */
@RestController
public class ProgressController {

    private static final Logger log = LoggerFactory.getLogger(ProgressController.class);

    // -----------------------------------------------------------
    // INFRASTRUCTURE CONSTANTS & STATE
    // -----------------------------------------------------------

    /**
     * Absolute maximum lifespan of an SSE connection.
     * Set to 1 Hour. Heavy local LLM inference or parsing 10GB of forensic logs
     * takes time. If we rely on Tomcat's default ~30s timeout, connections will sever prematurely.
     */
    private static final long SSE_TIMEOUT_MS = 3600000L;

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer redisListenerContainer;

    /**
     * Thread-safe registry mapping a Job ID to its active HTTP connection.
     * Kept strictly local to the node holding the user's TCP connection.
     */
    private final Map<String, SseEmitter> localEmitters = new ConcurrentHashMap<>();

    /**
     * Thread-safe registry mapping a Job ID to its active Redis subscription.
     * Required so we can cleanly unsubscribe from Redis when the HTTP client disconnects,
     * preventing severe memory leaks (Zombie Listeners).
     */
    private final Map<String, MessageListener> localListeners = new ConcurrentHashMap<>();

    /**
     * Ephemeral local buffer for network resilience.
     * If a client's Wi-Fi drops for 2 seconds and they reconnect, this cache allows us
     * to instantly replay missed progress ticks so the UI doesn't break.
     * Bounded by time (2 hours) and size (5000 keys) to protect JVM heap space.
     */
    private final Cache<String, List<String>> messageCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.HOURS)
            .maximumSize(5000)
            .build();

    public ProgressController(StringRedisTemplate redisTemplate, RedisMessageListenerContainer redisListenerContainer) {
        this.redisTemplate = redisTemplate;
        this.redisListenerContainer = redisListenerContainer;
    }

    // ==========================================
    // PUBLIC API BOUNDARIES
    // ==========================================

    /**
     * Client Endpoint: Opens a unidirectional event stream to the browser.
     * * @param jobId The correlation ID generated during the initial file upload.
     * @return The configured SseEmitter managing the HTTP stream.
     */
    @GetMapping("/api/jobs/{jobId}/stream")
    public SseEmitter streamProgress(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        localEmitters.put(jobId, emitter);

        // 1. Instantly replay any missed logs to catch the UI up to current state.
        sendCachedMessages(jobId, emitter);

        // 2. Wire this HTTP connection to the distributed Redis event bus.
        subscribeToRedisChannel(jobId, emitter);

        // 3. MEMORY SAFETY: Register mandatory lifecycle hooks.
        // If the browser closes, the network drops, or the job finishes,
        // we must explicitly garbage collect our Maps and Redis subscriptions.
        emitter.onCompletion(() -> cleanupConnection(jobId));
        emitter.onTimeout(() -> cleanupConnection(jobId));
        emitter.onError((e) -> cleanupConnection(jobId));

        return emitter;
    }

    /**
     * Worker Hook: Broadcasts a progress update to the cluster.
     * <p>
     * Note: The thread calling this method might be on an entirely different server
     * than the thread running `streamProgress`.
     *
     * @param jobId The target correlation ID.
     * @param message The progress payload (e.g., "Extracting PDF text (45%)...").
     */
    public void emitProgress(String jobId, String message) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, message);
    }

    /**
     * Worker Hook: Broadcasts the termination signal.
     */
    public void completeEmitter(String jobId) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, "Complete");
    }

    // ==========================================
    // PRIVATE INFRASTRUCTURE HELPERS
    // ==========================================

    /**
     * Bridges the Redis Pub/Sub framework to the Spring MVC SseEmitter.
     */
    private void subscribeToRedisChannel(String jobId, SseEmitter emitter) {
        String channelName = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;

        MessageListener listener = (Message message, byte[] pattern) -> {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);

            // Buffer locally before sending over the wire
            cacheMessageLocally(jobId, payload);

            try {
                emitter.send(SseEmitter.event().name("progress").data(payload));

                // If the worker signals completion, gracefully close the HTTP stream.
                if ("Complete".equals(payload)) {
                    emitter.complete();
                    cleanupConnection(jobId);
                }
            } catch (Exception e) {
                // Occurs frequently in standard operation (e.g., user navigates away from page).
                // Do not throw stack traces here, just quietly clean up.
                log.debug("Client disconnected during emit for job {}", jobId);
                cleanupConnection(jobId);
            }
        };

        // Save reference for future garbage collection, then attach to Redis.
        localListeners.put(jobId, listener);
        redisListenerContainer.addMessageListener(listener, new ChannelTopic(channelName));
    }

    /**
     * Appends a message to the local memory buffer.
     * Uses CopyOnWriteArrayList to prevent ConcurrentModificationExceptions if
     * Redis is appending logs on Thread A while `sendCachedMessages` is iterating on Thread B.
     */
    private void cacheMessageLocally(String jobId, String message) {
        List<String> logs = messageCache.get(jobId, k -> new CopyOnWriteArrayList<>());
        if (logs != null) {
            logs.add(message);
        }
    }

    /**
     * Flushes the memory buffer to a newly connected client.
     */
    private void sendCachedMessages(String jobId, SseEmitter emitter) {
        List<String> cachedMessages = messageCache.getIfPresent(jobId);
        if (cachedMessages != null) {
            for (String msg : cachedMessages) {
                try {
                    emitter.send(SseEmitter.event().name("progress").data(msg));
                } catch (Exception e) {
                    log.warn("Failed to send cached progress to client for job {}", jobId);
                    cleanupConnection(jobId);
                    return; // Halt iteration if the socket is already dead
                }
            }
        }
    }

    /**
     * Critical Garbage Collection Routine.
     * Removes all local references to the emitter and detaches the listener from Redis
     * to prevent CPU and Memory starvation.
     */
    private void cleanupConnection(String jobId) {
        localEmitters.remove(jobId);

        MessageListener listener = localListeners.remove(jobId);
        if (listener != null) {
            redisListenerContainer.removeMessageListener(listener);
        }
    }
}