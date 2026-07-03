package com.maheshz.ForensiX.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enterprise Asynchronous Configuration.
 * * This class establishes a dedicated, bounded thread pool for heavy AI and RAG ingestion workloads.
 * By isolating these tasks from the primary Tomcat HTTP thread pool, we prevent slow LLM
 * network calls or heavy document parsing from exhausting web server resources and causing
 * a complete application freeze.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Provisions the "aiTaskExecutor" thread pool.
     * Overrides Spring's default SimpleAsyncTaskExecutor (which uses an unbounded queue and
     * creates a new thread for every task, risking OutOfMemoryErrors under heavy load).
     *
     * @return A safely bounded, backpressure-aware Executor.
     */
    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // -----------------------------------------------------------
        // 1. HARDWARE-ALIGNED SCALING PARAMETERS
        // -----------------------------------------------------------
        // Baseline threads kept alive even when idle. Prevents the latency overhead
        // of spinning up new threads for intermittent document uploads.
        executor.setCorePoolSize(4);

        // Maximum concurrent threads. Capped at 8 to prevent CPU context-switching
        // thrashing during massive batch ingestion spikes.
        executor.setMaxPoolSize(8);

        // Bounded queue. This is a critical memory safety mechanism.
        // Limits the backlog to 25 tasks, preventing RAM exhaustion (OOM) if
        // the application is flooded with thousands of document uploads at once.
        executor.setQueueCapacity(25);

        // -----------------------------------------------------------
        // 2. OBSERVABILITY & DEBUGGING
        // -----------------------------------------------------------
        // Tags the threads in logs and APM tools (e.g., Datadog, Prometheus)
        // so we can easily distinguish AI background tasks from HTTP web traffic.
        executor.setThreadNamePrefix("AI-Worker-");

        // -----------------------------------------------------------
        // 3. BACKPRESSURE & CIRCUIT BREAKING
        // -----------------------------------------------------------
        // If all 8 threads are busy and the queue hits 25, default behavior is to throw
        // a TaskRejectedException (dropping the user's file).
        // CallerRunsPolicy acts as a natural throttle: it forces the thread that submitted
        // the task (the Tomcat web thread) to execute it synchronously. This slows down the
        // intake of new HTTP requests until the backlog clears, keeping the system stable.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // -----------------------------------------------------------
        // 4. GRACEFUL SHUTDOWN
        // -----------------------------------------------------------
        // When the JVM receives a SIGTERM (e.g., during a Docker container redeploy),
        // do not brutally murder active threads that might be halfway through writing
        // vectors to the database (which causes data corruption).
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // Allow a 60-second bleed-off window for existing tasks to finish saving
        // to PostgreSQL before finally killing the process.
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        return executor;
    }
}