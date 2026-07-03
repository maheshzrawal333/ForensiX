package com.maheshz.ForensiX.engine.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enterprise Security Perimeter and Context Initializer.
 * <p>
 * This interceptor sits at the very edge of the Spring MVC request lifecycle.
 * Before any controller, service, or database repository is invoked, this class acts
 * as the mandatory gateway, ensuring that every incoming HTTP request explicitly
 * declares which Investigative Case (Tenant) it intends to interact with.
 * <p>
 * ARCHITECTURAL PURPOSE:
 * By extracting and validating the multi-tenant routing key here, we decouple
 * context-extraction logic from our business controllers, adhering to the
 * Single Responsibility Principle (SRP) and ensuring security is applied globally.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    /**
     * The designated HTTP header for cryptographic case boundary routing.
     * All API clients must inject this header to establish their operational perimeter.
     */
    private static final String TENANT_HEADER = "X-Tenant-ID";

    /**
     * Intercepts the execution chain BEFORE the target controller is invoked.
     * <p>
     * Enforces a "Fail-Fast" security posture. If the request lacks the required context,
     * it is immediately rejected with a 400 Bad Request, protecting downstream AI and
     * database resources from processing malformed queries.
     *
     * @param request  The incoming HTTP request payload.
     * @param response The outbound HTTP response payload.
     * @param handler  The target controller method mapped to this request.
     * @return {@code true} if the request is allowed to proceed; {@code false} to block it.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestedTenantId = request.getHeader(TENANT_HEADER);

        // -----------------------------------------------------------
        // 1. PERIMETER VALIDATION
        // -----------------------------------------------------------
        if (requestedTenantId == null || requestedTenantId.isBlank()) {
            log.warn("Security Block: Missing X-Tenant-ID header. Request rejected at perimeter.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return false;
        }

        // -----------------------------------------------------------
        // 2. AUTHORIZATION CHECK (TECHNICAL DEBT / DEV MODE)
        // -----------------------------------------------------------
        // 🚨 WARNING: CRITICAL PRODUCTION VULNERABILITY 🚨
        // Currently, we are blindly trusting the client to tell us who they are.
        // An attacker could easily pass `X-Tenant-ID: CASE-999` and gain access to another
        // investigator's files.
        //
        // PRODUCTION MIGRATION PATH:
        // Before deployment, this section MUST be refactored to extract the user's JWT
        // (JSON Web Token), verify its cryptographic signature, and ensure the `requestedTenantId`
        // exists within the `allowed_cases` array claim of the token.

        // -----------------------------------------------------------
        // 3. CONTEXT INITIALIZATION
        // -----------------------------------------------------------
        // Bind the validated Case ID to the current Tomcat worker thread.
        TenantContextHolder.setTenantId(requestedTenantId);

        return true;
    }

    /**
     * Lifecycle Hook: Executes AFTER the controller has finished processing and the response
     * has been flushed to the client (regardless of whether an exception occurred).
     * <p>
     * CRITICAL SECURITY GARBAGE COLLECTION:
     * Tomcat uses a Thread Pool to handle concurrent HTTP requests. When a request finishes,
     * its thread is not killed; it is returned to the pool for the next user.
     * If we do not explicitly clear the {@link TenantContextHolder} here, the next user
     * assigned to this thread will implicitly inherit the previous user's Case ID,
     * resulting in a catastrophic cross-tenant data breach.
     *
     * @param request  The incoming HTTP request.
     * @param response The outbound HTTP response.
     * @param handler  The controller method that was executed.
     * @param ex       Any exception thrown during execution (can be null).
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Guarantee the thread-local context is wiped to prevent data leaks across recycled HTTP threads.
        TenantContextHolder.clear();
    }
}