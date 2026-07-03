package com.maheshz.ForensiX.engine.security;

/**
 * Enterprise Thread-Local Storage for Multi-Tenant Context.
 * <p>
 * In a servlet-based application (like Spring Boot on Tomcat), each incoming HTTP request
 * is processed by a dedicated worker thread. This utility leverages {@link ThreadLocal}
 * to safely bind the active Case ID (Tenant ID) to the current execution thread.
 * <p>
 * ARCHITECTURAL PURPOSE:
 * By storing the tenant context here (populated by the {@code TenantInterceptor}),
 * we eliminate the need to pass the `tenantId` manually through every single method signature
 * in the Controller -> Service -> Repository call chain. The downstream services and data
 * access layers can simply query this holder to dynamically enforce row-level security boundaries.
 */
public class TenantContextHolder {

    /**
     * SECURITY CRITICAL: The ThreadLocal state variable.
     * <p>
     * Ensures that the stored tenant ID is strictly mathematically isolated to the thread
     * executing the current request. Thread A (processing an evidence upload for Case 101)
     * cannot access or overwrite the value in Thread B (querying vectors for Case 202).
     */
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    /**
     * Binds a specific Tenant ID to the current thread.
     * <p>
     * This should ONLY be called at the absolute outer edge of the application boundary
     * (e.g., inside the {@code preHandle} method of a web interceptor or security filter)
     * after the requested case ID has been validated against the database.
     *
     * @param tenantId The unique identifier of the validated case/tenant.
     */
    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * Retrieves the Tenant ID currently bound to the execution thread.
     * <p>
     * Called primarily by the Service or Repository layers to enforce data isolation
     * rules (e.g., appending the tenant ID to vector database searches or S3 object keys).
     *
     * @return The active tenant ID, or {@code null} if no context has been established.
     */
    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * SECURITY AND MEMORY LEAK PREVENTION: Clears the context.
     * <p>
     * Tomcat and other web servers utilize Thread Pools for high concurrency. When a request
     * finishes, its thread is not destroyed; it is recycled and returned to the pool to be
     * used by a completely different future request.
     * <p>
     * CRITICAL INVARIANT: If this method is not explicitly called in a {@code finally} block
     * or the {@code afterCompletion} phase of an interceptor, the next request assigned to
     * this thread will accidentally inherit the previous user's Case ID. This would result
     * in a severe cross-tenant data breach.
     */
    public static void clear() {
        TENANT_ID.remove();
    }
}