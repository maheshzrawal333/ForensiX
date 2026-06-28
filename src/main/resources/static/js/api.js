const ApiService = {
    uploadEvidence: async (file, folderId, caseId) => {
        const formData = new FormData();
        formData.append("file", file);
        formData.append("folderId", folderId);
        const response = await fetch('/api/knowledge/upload', { method: 'POST', headers: { 'X-Tenant-ID': caseId }, body: formData });
        if (!response.ok) throw new Error("Upload failed");
        return await response.json();
    },
    askStructuredQuestion: async (question, folderId, caseId, modelName) => {
        const response = await fetch('/api/forensics/analyze', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId }, body: JSON.stringify({ question, folderId, model: modelName }) });
        if (!response.ok) throw new Error("Analysis failed"); return await response.json();
    },
    generateReport: async (validatedEvidenceList, caseId, modelName) => {
        const response = await fetch('/api/forensics/report', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId }, body: JSON.stringify({ evidence: validatedEvidenceList, model: modelName }) });
        if (!response.ok) throw new Error("Report generation failed"); return await response.json();
    },
    getFiles: async (folderId, caseId) => {
        const queryFolder = folderId === "root" ? "root" : folderId;
        const response = await fetch(`/api/knowledge/documents?folderId=${queryFolder}`, { method: 'GET', headers: { 'X-Tenant-ID': caseId }});
        if (!response.ok) throw new Error("Fetch failed"); return await response.json();
    },
    deleteFile: async (documentId, caseId) => {
        const response = await fetch(`/api/knowledge/documents/${documentId}`, { method: 'DELETE', headers: { 'X-Tenant-ID': caseId }});
        if (!response.ok) throw new Error("Delete failed"); return true;
    },
    getFolders: async (parentFolderId, caseId) => {
        let url = `/api/folders` + (parentFolderId && parentFolderId !== "root" ? `?parentFolderId=${encodeURIComponent(parentFolderId)}` : "");
        const response = await fetch(url, { method: 'GET', headers: { 'X-Tenant-ID': caseId }});
        if (!response.ok) throw new Error("Fetch folders failed"); return await response.json();
    },
    createFolder: async (name, parentFolderId, caseId) => {
        let url = `/api/folders?name=${encodeURIComponent(name)}` + (parentFolderId && parentFolderId !== "root" ? `&parentFolderId=${encodeURIComponent(parentFolderId)}` : "");
        const response = await fetch(url, { method: 'POST', headers: { 'X-Tenant-ID': caseId }});
        if (!response.ok) throw new Error("Create folder failed"); return await response.json();
    },
    renameFolder: async (folderId, caseId, newName) => {
        const response = await fetch(`/api/folders/${folderId}/rename`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', 'X-Tenant-ID': caseId },
            body: JSON.stringify({ name: newName })
        });
        if (!response.ok) throw new Error("Rename failed"); return await response.json();
    },
    deleteFolder: async (folderId, caseId) => {
        const response = await fetch(`/api/folders/${folderId}`, { method: 'DELETE', headers: { 'X-Tenant-ID': caseId }});
        if (!response.ok) throw new Error("Delete failed. Folder might not be empty."); return true;
    },
    getTenants: async () => {
        const response = await fetch('/api/admin/tenants');
        if (!response.ok) throw new Error("Fetch cases failed"); return await response.json();
    },
    // SENIOR FIX: Restored the missing API method
    createTenant: async (id, name) => {
        const response = await fetch(`/api/admin/tenants?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name)}`, { method: 'POST', headers: { 'X-Tenant-ID': id }});
        if (!response.ok) throw new Error("Create case failed"); return await response.json();
    },
    getModels: async () => {
        try {
            const response = await fetch('/api/admin/models');
            if (response.ok) return await response.json();
        } catch (e) {
            console.warn("Model endpoint unavailable, falling back to safe local default.");
        }
        return ["llama3.2:3b"];
    }
};