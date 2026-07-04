/**
 * Enterprise API Service Gateway
 * * This module serves as the single source of truth for all outbound network requests
 * from the ForensiX frontend. By centralizing the `fetch` logic here, we ensure
 * consistent error handling, unified timeout management, and mathematical enforcement
 * of our multi-tenant security headers.
 */
const ApiService = {

    /**
     * Centralized Network Interceptor and Fetch Wrapper.
     * * ARCHITECTURAL DESIGN:
     * We wrap the native `fetch` API to enforce strict lifecycle management on network connections.
     * Browsers do not time out fetch requests by default; if the backend hangs, the frontend
     * will hang infinitely, resulting in memory leaks and frozen UIs. We utilize `AbortController`
     * to forcefully sever dead connections.
     * * @param {string} endpoint - The relative API path (e.g., '/api/knowledge').
     * @param {Object} options - Standard fetch options (method, headers, body).
     * @param {number} timeoutMs - Hyperparameter: Maximum allowed TCP wait time. Defaults to 15s.
     * @returns {Promise<any>} The parsed JSON payload or a boolean for 204 responses.
     */
    _fetch: async (endpoint, options = {}, timeoutMs = 15000) => {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

        // ARCHITECTURAL DEBT NOTE (#2):
        // In a production environment, reading JWTs from localStorage is a critical security
        // vulnerability (CWE-79), as any malicious script (XSS) can steal it.
        // MIGRATION PATH: Move to HttpOnly, SameSite=Strict cookies set by the backend.
        const token = localStorage.getItem('auth_token');
        const headers = {
            ...options.headers,
            ...(token && { 'Authorization': `Bearer ${token}` })
        };

        try {
            const response = await fetch(endpoint, {
                ...options,
                headers,
                signal: controller.signal
            });

            // ROBUST ERROR HANDLING:
            // Instead of just throwing a generic "500 Error", we attempt to parse
            // Spring Boot's RFC 7807 ProblemDetail JSON response. This surfaces the exact
            // backend exception message (e.g., "File already exists") directly to the UI.
            if (!response.ok) {
                const errorData = await response.json().catch(() => ({}));
                throw new Error(errorData.detail || errorData.message || `HTTP ${response.status} - ${response.statusText}`);
            }

            // HTTP 204 (No Content) usually occurs on successful DELETE operations.
            // Attempting to run `.json()` on a 204 will crash the JS execution thread.
            if (response.status === 204) return true;

            return await response.json();
        } catch (error) {
            // Translate the cryptic DOMException into a UX-friendly timeout message
            if (error.name === 'AbortError') {
                throw new Error(`Request timed out after ${timeoutMs / 1000} seconds.`);
            }
            throw error;
        } finally {
            // GARBAGE COLLECTION:
            // Always clear the timeout ID regardless of success or failure to prevent
            // memory leaks in the browser's event loop.
            clearTimeout(timeoutId);
        }
    },

    // ==========================================
    // INGESTION & EVIDENCE API
    // ==========================================

    /**
     * Streams a raw binary payload to the backend for AI vectorization.
     * Note: We deliberately DO NOT set the 'Content-Type' header here. Passing `FormData`
     * allows the browser to automatically calculate and inject the correct multipart boundary.
     */
    uploadEvidence: async (file, folderId, caseId) => {
        const formData = new FormData();
        formData.append("file", file);
        formData.append("folderId", folderId);

        // Uploads are network-bound and take time. We give the initial TCP push 30 seconds.
        // The actual vectorization happens asynchronously via Redis/SSE after this returns.
        return await ApiService._fetch('/api/knowledge/upload', {
            method: 'POST',
            headers: { 'X-Tenant-ID': caseId },
            body: formData
        }, 30000);
    },

    getFiles: async (folderId, caseId) => {
        // DEFENSIVE PROGRAMMING: Encode URI components to prevent path traversal
        // or HTTP parameter pollution if a folder name contains special characters (&, ?, =).
        const queryFolder = folderId === "root" ? "root" : encodeURIComponent(folderId);
        return await ApiService._fetch(`/api/knowledge/documents?folderId=${queryFolder}`, {
            method: 'GET',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    deleteFile: async (documentId, caseId) => {
        return await ApiService._fetch(`/api/knowledge/documents/${encodeURIComponent(documentId)}`, {
            method: 'DELETE',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    // ==========================================
    // FOLDER HIERARCHY API
    // ==========================================

    getFolders: async (parentFolderId, caseId) => {
        const url = `/api/folders` + (parentFolderId && parentFolderId !== "root" ? `?parentFolderId=${encodeURIComponent(parentFolderId)}` : "");
        return await ApiService._fetch(url, {
            method: 'GET',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    createFolder: async (name, parentFolderId, caseId) => {
        const payload = {
            name: name,
            parentFolderId: parentFolderId === "root" ? null : parentFolderId
        };

        return await ApiService._fetch('/api/folders', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Tenant-ID': caseId
            },
            body: JSON.stringify(payload)
        });
    },

    renameFolder: async (folderId, caseId, newName) => {
        return await ApiService._fetch(`/api/folders/${encodeURIComponent(folderId)}/rename`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId },
            body: JSON.stringify({ name: newName })
        });
    },

    deleteFolder: async (folderId, caseId) => {
        return await ApiService._fetch(`/api/folders/${encodeURIComponent(folderId)}`, {
            method: 'DELETE',
            headers: { 'X-Tenant-ID': caseId }
        });
    },

    // ==========================================
    // AI FORENSICS API (High-Latency Endpoints)
    // ==========================================

    /**
     * Dispatches a semantic query to the Retrieval-Augmented Generation (RAG) pipeline.
     */
    askStructuredQuestion: async (question, folderIds, caseId, modelName) => {
        // CRITICAL TUNING: Increased timeout to 300,000ms (5 minutes).
        // Processing massive tabular data (CSVs) or dense context windows via local LLMs
        // is heavily compute-bound (GPU/CPU). If we use the default 15s timeout here,
        // the frontend will silently drop the connection right before the LLM finishes generating.
        return await ApiService._fetch('/api/forensics/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId },
            body: JSON.stringify({ question, folderIds, model: modelName })
        }, 300000);
    },

    /**
     * Synthesizes user-validated facts into a cohesive chronological narrative.
     */
    generateReport: async (validatedEvidenceList, caseId, modelName) => {
        // Report synthesis requires less database retrieval but high LLM token generation.
        // 120 seconds provides a safe buffer for slower, quantized models.
        return await ApiService._fetch('/api/forensics/report', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId },
            body: JSON.stringify({ evidence: validatedEvidenceList, model: modelName })
        }, 120000);
    },

    // ==========================================
    // SYSTEM & TENANT ADMIN API
    // ==========================================

    getTenants: async () => {
        return await ApiService._fetch('/api/admin/tenants', { method: 'GET' });
    },

    createTenant: async (id, name) => {
        return await ApiService._fetch(`/api/admin/tenants?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name)}`, {
            method: 'POST',
            headers: { 'X-Tenant-ID': id }
        });
    },

    /**
     * Discovers available LLM models on the host machine.
     */
    getModels: async () => {
        try {
            // Extremely short timeout (3s). Model discovery should be near-instantaneous.
            // If the backend doesn't respond immediately, we assume the Ollama socket is
            // temporarily busy and fall back to cache.
            return await ApiService._fetch('/api/admin/models', { method: 'GET' }, 3000);
        } catch (e) {
            // GRACEFUL DEGRADATION:
            // If the backend endpoint fails, we do not crash the UI. We silently catch the
            // error and provide a hardcoded array of standard offline models so the user
            // can continue interacting with the app.
            console.warn("Backend model endpoint unavailable. Failing over to local offline inventory.", e);
            return [
                "llama3.2:3b",
                "qwen2.5-coder:7b",
                "deepseek-r1:8b",
                "deepseek-coder-v2:16b"
            ];
        }
    }
};