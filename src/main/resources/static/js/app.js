/**
 * ForensiX - Human-In-The-Loop Architecture
 */

let chatHistory = {};
let activeMessageId = null;
let verifiedFacts = [];
let selectedContexts = new Map();

const elements = {
    caseId: document.getElementById('caseId'),
    fileTreeContainer: document.getElementById('fileTreeContainer'),
    chatWindow: document.getElementById('chatWindow'),
    questionInput: document.getElementById('questionInput'),
    askBtn: document.getElementById('askBtn'),
    modelSelector: document.getElementById('modelSelector'),
    reasonContainer: document.getElementById('reasonContainer'),
    validBtn: document.getElementById('validBtn'),
    reportContainer: document.getElementById('reportContainer'),
    generateReportBtn: document.getElementById('generateReportBtn'),
    verifiedCount: document.getElementById('verifiedCount')
};

// UTILITY: Safe class toggling avoids silent failures of classList.replace
function swapClass(el, oldCls, newCls) {
    if (!el) return;
    if (oldCls) el.classList.remove(oldCls);
    if (newCls) el.classList.add(newCls);
}

// UTILITY: Security escape for untrusted AI & File System data
function escapeHTML(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

const CustomModal = {
    _timeoutId: null, // SENIOR FIX: Track timeout to prevent race conditions

    show: (title, message, type, confirmBtnText, confirmBtnColorClass, onConfirmCallback) => {
        if (CustomModal._timeoutId) clearTimeout(CustomModal._timeoutId);

        const overlay = document.getElementById('modalOverlay');
        const content = document.getElementById('modalContent');
        const input = document.getElementById('modalInput');
        const confirmBtn = document.getElementById('modalConfirm');

        // innerText is naturally safe from XSS
        document.getElementById('modalTitle').innerText = title;
        document.getElementById('modalBody').innerText = message;

        confirmBtn.className = `px-4 py-2 rounded text-white font-bold transition-colors shadow ${confirmBtnColorClass}`;
        confirmBtn.innerText = confirmBtnText;

        if (type === 'prompt') {
            input.classList.remove('hidden');
            input.value = '';
            setTimeout(() => input.focus(), 50);
        } else {
            input.classList.add('hidden');
        }

        overlay.classList.remove('hidden');
        overlay.classList.add('flex');

        setTimeout(() => swapClass(content, 'scale-95', 'scale-100'), 10);

        const newConfirmBtn = confirmBtn.cloneNode(true);
        confirmBtn.parentNode.replaceChild(newConfirmBtn, confirmBtn);

        newConfirmBtn.addEventListener('click', () => {
            const val = input.value;
            CustomModal.close();
            if(type === 'prompt') onConfirmCallback(val);
            else onConfirmCallback();
        });

        document.getElementById('modalCancel').onclick = CustomModal.close;
    },
    close: () => {
        if (CustomModal._timeoutId) clearTimeout(CustomModal._timeoutId);

        const overlay = document.getElementById('modalOverlay');
        const content = document.getElementById('modalContent');

        swapClass(content, 'scale-100', 'scale-95');

        CustomModal._timeoutId = setTimeout(() => {
            overlay.classList.remove('flex');
            overlay.classList.add('hidden');
        }, 150);
    }
};

document.addEventListener("DOMContentLoaded", async () => {
    elements.askBtn.addEventListener("click", handleAsk);
    elements.questionInput.addEventListener("keypress", (e) => { if (e.key === 'Enter') handleAsk(); });
    elements.validBtn.addEventListener("click", handleValidateReason);
    elements.generateReportBtn.addEventListener("click", handleGenerateReport);

    document.getElementById('refreshBtn').addEventListener("click", refreshTreeState);
    document.getElementById('newFolderBtn').addEventListener("click", handleCreateFolder);
    document.getElementById('newCaseBtn').addEventListener("click", handleCreateCase);
    elements.caseId.addEventListener("change", resetCaseContext);

    document.getElementById('globalUploadBtn').addEventListener('click', handleGlobalUpload);
    document.getElementById('globalRenameBtn').addEventListener('click', handleGlobalRename);
    document.getElementById('globalDeleteBtn').addEventListener('click', handleGlobalDelete);
    document.getElementById('fileInput').addEventListener('change', window.handleUpload);

    initViewControllers();
    await loadModels();
    await loadCases();
    resetCaseContext();
});

function initViewControllers() {
    function toggleColumn(colId, btnId) {
        const column = document.getElementById(colId);
        const btn = document.getElementById(btnId);
        if(!column || !btn) return;

        column.classList.toggle('hidden');
        if (column.classList.contains('hidden')) {
            swapClass(btn, 'bg-blue-600', 'bg-slate-700');
            swapClass(btn, 'text-white', 'text-slate-400');
            btn.classList.add('opacity-50');
        } else {
            swapClass(btn, 'bg-slate-700', 'bg-blue-600');
            swapClass(btn, 'text-slate-400', 'text-white');
            btn.classList.remove('opacity-50');
        }
    }
    document.getElementById('toggleCol1')?.addEventListener('click', () => toggleColumn('col-1', 'toggleCol1'));
    document.getElementById('toggleCol2')?.addEventListener('click', () => toggleColumn('col-2', 'toggleCol2'));
    document.getElementById('toggleCol3')?.addEventListener('click', () => toggleColumn('col-3', 'toggleCol3'));
}

async function loadModels() {
    const models = await ApiService.getModels();
    elements.modelSelector.innerHTML = models.map(m => `<option value="${escapeHTML(m)}">${escapeHTML(m)}</option>`).join('');
}

function resetCaseContext() {
    chatHistory = {};
    activeMessageId = null;
    verifiedFacts = [];
    selectedContexts.clear();

    updateContextBanner();
    updateVerifiedCounter();

    elements.chatWindow.innerHTML = `<div class="text-slate-500 text-sm italic text-center mt-2">Initialize query...</div>`;
    elements.reasonContainer.innerHTML = `<p class="text-slate-500 italic text-center mt-4">Select an AI response to view its forensic reasoning.</p>`;
    elements.reportContainer.innerHTML = `<p class="text-slate-500 italic">Click 'Go' to synthesize a complete case narrative based ONLY on validated evidence.</p>`;
    elements.validBtn.disabled = true;

    loadRootDirectory();
}

window.toggleContextSelection = (event, id, name, type) => {
    event.stopPropagation();
    if (event.target.checked) {
        selectedContexts.set(id, { name: name, type: type });
    } else {
        selectedContexts.delete(id);
    }
    updateContextBanner();
};

function updateContextBanner() {
    const displayElement = document.getElementById('activeContextDisplay');
    if (selectedContexts.size === 0) {
        displayElement.innerHTML = `<span class="bg-slate-800 px-2 py-1 rounded text-blue-300 shadow-inner">/Root (Entire Case)</span>`;
    } else {
        // SENIOR FIX: Escape injected HTML payload
        let tagsHtml = Array.from(selectedContexts.values()).map(item => {
            const icon = item.type === 'folder' ? '📁' : getFileIcon(item.name);
            return `<span class="bg-blue-900/80 border border-blue-500 text-blue-100 px-2 py-0.5 rounded text-[11px] mr-1 mb-1 inline-block shadow-sm">${icon} ${escapeHTML(item.name)}</span>`;
        }).join('');
        displayElement.innerHTML = tagsHtml;
    }
}

async function loadCases(selectTargetId = null) {
    if (!elements.caseId) return;
    try {
        const cases = await ApiService.getTenants();
        window.activeCases = cases;

        if (cases.length === 0) {
            elements.caseId.innerHTML = `<option value="DEFAULT-CASE">DEFAULT-CASE</option>`;
            return;
        }

        elements.caseId.innerHTML = cases.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.id)} - ${escapeHTML(c.name)}</option>`).join('');
        if (selectTargetId) elements.caseId.value = selectTargetId;
    } catch (e) {
        elements.caseId.innerHTML = `<option value="DEFAULT-CASE">Offline Mode</option>`;
        window.activeCases = [];
    }
}

async function handleCreateCase() {
    CustomModal.show(
        "New Investigation Case",
        "Enter a secure name for the new investigation case:",
        "prompt",
        "Create Case",
        "bg-blue-600 hover:bg-blue-500",
        async (caseName) => {
            if (!caseName) return;

            let caseId;
            const cases = window.activeCases || [];
            do {
                caseId = "CASE-" + Math.floor(1000 + Math.random() * 9000);
            } while (cases.some(c => c.id === caseId));

            try {
                await ApiService.createTenant(caseId, caseName);
                await loadCases(caseId);
                resetCaseContext();
            } catch (e) {
                CustomModal.show("Error", "Failed to create new case.", "alert", "OK", "bg-red-600", ()=>{});
            }
        }
    );
}

async function refreshTreeState() {
    const openFolders = Array.from(document.querySelectorAll('.tree-line:not(.hidden)'))
        .map(el => el.id.replace('children-', ''));

    await loadRootDirectory();

    for (const folderId of openFolders) {
        if (folderId === 'root') continue;
        const childrenContainer = document.getElementById(`children-${folderId}`);
        const expanderIcon = document.getElementById(`expander-${folderId}`);

        if (childrenContainer && childrenContainer.classList.contains('hidden')) {
            childrenContainer.classList.remove('hidden');
            if (expanderIcon) expanderIcon.innerText = '▼';
            await expandFolder(folderId, childrenContainer);
        }
    }
}

async function loadRootDirectory() {
    // SENIOR FIX: Moved loading state to initial assignment to fix silent dead assignment
    elements.fileTreeContainer.innerHTML = `
        <div id="node-root" class="tree-node p-1.5 rounded flex items-center gap-2 border border-transparent">
            <span class="text-slate-400">🗄️</span> 
            <span class="font-bold text-blue-300">Case Root</span>
        </div>
        <div id="children-root" class="tree-line">
            <div class="text-center text-slate-500 text-xs mt-2 animate-pulse">Loading Tree...</div>
        </div>
    `;
    await expandFolder("root", document.getElementById("children-root"));
}

function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    if (['pdf'].includes(ext)) return '📕';
    if (['csv', 'xlsx', 'xls'].includes(ext)) return '📊';
    if (['png', 'jpg', 'jpeg'].includes(ext)) return '🖼️';
    if (['doc', 'docx', 'txt'].includes(ext)) return '📝';
    return '📄';
}

async function expandFolder(folderId, containerElement) {
    const caseId = elements.caseId.value;

    try {
        const [folders, files] = await Promise.all([
            ApiService.getFolders(folderId, caseId),
            ApiService.getFiles(folderId, caseId)
        ]);

        containerElement.innerHTML = '';
        if (folders.length === 0 && files.length === 0) {
            containerElement.innerHTML = '<div class="text-slate-600 text-xs italic py-1">Empty</div>';
            return;
        }

        folders.forEach(folder => {
            const isChecked = selectedContexts.has(folder.id) ? 'checked' : '';
            // SENIOR FIX: XSS protection and robust event parameters using dataset
            const folderHtml = `
                <div class="mt-1">
                    <div id="node-${folder.id}" tabindex="0" role="button" onkeydown="if(event.key==='Enter'||event.key===' ') { event.preventDefault(); toggleFolderExpander(event, '${folder.id}'); }" class="tree-node p-1 rounded flex items-center border border-transparent hover:bg-slate-700/50 focus:bg-slate-700/50 focus:outline-none transition-colors">
                        <span id="expander-${folder.id}" onclick="toggleFolderExpander(event, '${folder.id}')" class="text-slate-400 hover:text-white cursor-pointer px-1 w-4 text-center shrink-0">▶</span>
                        <input type="checkbox" ${isChecked} tabindex="-1" class="w-3 h-3 cursor-pointer shrink-0 accent-blue-500 mr-2 ml-1" onchange="toggleContextSelection(event, '${folder.id}', this.dataset.name, 'folder')" data-name="${escapeHTML(folder.name)}">
                        <span class="text-yellow-500 shrink-0 text-sm mr-1 cursor-pointer" onclick="toggleFolderExpander(event, '${folder.id}')">📁</span> 
                        <span class="text-sm text-slate-200 select-none pr-4 cursor-pointer" onclick="toggleFolderExpander(event, '${folder.id}')" title="${escapeHTML(folder.name)}">${escapeHTML(folder.name)}</span>
                    </div>
                    <div id="children-${folder.id}" class="tree-line hidden"></div>
                </div>
            `;
            containerElement.insertAdjacentHTML('beforeend', folderHtml);
        });

        files.forEach(file => {
            const isChecked = selectedContexts.has(file.id) ? 'checked' : '';
            const icon = getFileIcon(file.fileName);
            // SENIOR FIX: XSS protection and robust event parameters using dataset
            const fileHtml = `
                <div tabindex="0" role="button" onkeydown="if(event.key==='Enter'||event.key===' ') { event.preventDefault(); const cb = this.querySelector('input'); cb.checked = !cb.checked; cb.dispatchEvent(new Event('change')); }" class="py-1 px-2 flex items-center hover:bg-slate-700/30 focus:bg-slate-700/50 focus:outline-none rounded mt-1 transition-colors">
                    <input type="checkbox" ${isChecked} tabindex="-1" class="w-3 h-3 cursor-pointer shrink-0 accent-blue-500 mr-2 ml-5" onchange="toggleContextSelection(event, '${file.id}', this.dataset.name, 'file')" data-name="${escapeHTML(file.fileName)}">
                    <span class="text-xs shrink-0 mr-1 opacity-90">${icon}</span>
                    <span class="text-xs select-text pr-4 break-words" title="${escapeHTML(file.fileName)}">${escapeHTML(file.fileName)}</span>
                </div>
            `;
            containerElement.insertAdjacentHTML('beforeend', fileHtml);
        });
    } catch (error) {
        containerElement.innerHTML = `<div class="text-red-400 text-xs">Error loading hierarchy</div>`;
    }
}

window.toggleFolderExpander = async (event, folderId) => {
    event.stopPropagation();
    const childrenContainer = document.getElementById(`children-${folderId}`);
    const expanderIcon = document.getElementById(`expander-${folderId}`);

    if (childrenContainer.classList.contains('hidden')) {
        childrenContainer.classList.remove('hidden');
        if (expanderIcon) expanderIcon.innerText = '▼';
        if (childrenContainer.innerHTML.trim() === '') await expandFolder(folderId, childrenContainer);
    } else {
        childrenContainer.classList.add('hidden');
        if (expanderIcon) expanderIcon.innerText = '▶';
    }
};

async function handleCreateFolder() {
    let targetFolderId = "root";
    let targetFolderName = "Root";

    const selected = Array.from(selectedContexts.entries());
    if (selected.length > 1) {
        return CustomModal.show("Action Blocked", "Please select only ONE folder to create a new folder inside.", "alert", "OK", "bg-blue-600", () => {});
    } else if (selected.length === 1) {
        if (selected[0][1].type !== 'folder') {
            return CustomModal.show("Action Blocked", "You cannot create a folder inside a file. Select a folder instead.", "alert", "OK", "bg-blue-600", () => {});
        }
        targetFolderId = selected[0][0];
        targetFolderName = selected[0][1].name;
    }

    CustomModal.show(
        "Create Directory",
        `Creating a new folder inside: /${targetFolderName}`,
        "prompt",
        "Create Folder",
        "bg-blue-600 hover:bg-blue-500",
        async (folderName) => {
            if (!folderName || folderName.trim() === "") return;
            try {
                await ApiService.createFolder(folderName.trim(), targetFolderId, elements.caseId.value);
                await refreshTreeState();
            } catch (e) {
                CustomModal.show("Error", "Failed to create folder.", "alert", "OK", "bg-red-600", ()=>{});
            }
        }
    );
}

function handleGlobalUpload() {
    let targetFolderId = "root";
    const selected = Array.from(selectedContexts.entries());

    if (selected.length > 1) {
        return CustomModal.show("Notice", "Please check only ONE folder to upload evidence into.", "alert", "OK", "bg-blue-600", ()=>{});
    } else if (selected.length === 1) {
        if (selected[0][1].type !== 'folder') {
            return CustomModal.show("Notice", "You cannot upload evidence directly into a file. Check a folder instead.", "alert", "OK", "bg-blue-600", ()=>{});
        }
        targetFolderId = selected[0][0];
    }

    window.targetUploadFolderId = targetFolderId;
    document.getElementById('fileInput').click();
}

window.handleUpload = async (event) => {
    const files = event.target.files;
    if (files.length === 0) return;
    const targetFolderId = window.targetUploadFolderId || "root";

    const originalText = document.getElementById('activeContextDisplay').innerHTML;
    document.getElementById('activeContextDisplay').innerHTML = `<span class="text-yellow-400 font-bold bg-slate-800 px-2 py-1 rounded animate-pulse">Uploading ${files.length} items...</span>`;

    for (let i = 0; i < files.length; i++) {
        try {
            const data = await ApiService.uploadEvidence(files[i], targetFolderId, elements.caseId.value);

            await new Promise((resolve, reject) => {
                const eventSource = new EventSource(`/api/jobs/${data.jobId}/stream`);

                const streamTimeout = setTimeout(() => {
                    eventSource.close();
                    reject(new Error("Stream timed out from backend."));
                }, 60000);

                eventSource.onmessage = (e) => {
                    if (e.data === 'Complete') {
                        clearTimeout(streamTimeout);
                        eventSource.close();
                        resolve();
                    }
                };
                eventSource.onerror = () => {
                    clearTimeout(streamTimeout);
                    eventSource.close();
                    resolve();
                };
            });
        } catch (error) {
            console.error("Upload failed for", files[i].name, error);
            CustomModal.show("Upload Issue", `Error processing: ${escapeHTML(files[i].name)}. Moving to next file.`, "alert", "OK", "bg-yellow-600", ()=>{});
        }
    }

    event.target.value = '';
    document.getElementById('activeContextDisplay').innerHTML = originalText;
    await refreshTreeState();
};

async function handleGlobalRename() {
    const selected = Array.from(selectedContexts.entries());
    if (selected.length !== 1) {
        return CustomModal.show("Notice", "Please select exactly ONE folder to rename.", "alert", "OK", "bg-blue-600", ()=>{});
    }

    const [id, data] = selected[0];
    if (data.type !== 'folder') {
        return CustomModal.show("Notice", "Currently, only folders can be renamed.", "alert", "OK", "bg-blue-600", ()=>{});
    }

    CustomModal.show(
        "Rename Folder",
        `Enter a new name for "${data.name}":`,
        "prompt",
        "Rename",
        "bg-yellow-600 hover:bg-yellow-500",
        async (newName) => {
            if (!newName || newName === data.name) return;
            try {
                await ApiService.renameFolder(id, elements.caseId.value, newName);
                selectedContexts.set(id, { name: newName, type: 'folder' });
                updateContextBanner();
                await refreshTreeState();
            } catch (e) {
                CustomModal.show("Error", "Failed to rename folder. It might be locked by the system.", "alert", "OK", "bg-red-600", ()=>{});
            }
        }
    );
}

async function handleGlobalDelete() {
    if (selectedContexts.size === 0) {
        return CustomModal.show("Notice", "Please select at least one item to delete.", "alert", "OK", "bg-blue-600", ()=>{});
    }

    CustomModal.show(
        "CRITICAL ACTION",
        `Are you sure you want to permanently wipe ${selectedContexts.size} selected item(s) and their AI vectors? This cannot be undone.`,
        "confirm",
        "Wipe Data",
        "bg-red-600 hover:bg-red-500 text-white",
        async () => {
            let hasError = false;
            let successIds = [];

            for (const [id, data] of selectedContexts.entries()) {
                try {
                    if (data.type === 'folder') {
                        await ApiService.deleteFolder(id, elements.caseId.value);
                    } else {
                        await ApiService.deleteFile(id, elements.caseId.value);
                    }
                    successIds.push(id);
                } catch (e) {
                    hasError = true;
                }
            }

            if (hasError) {
                CustomModal.show("Partial Success", "Some items could not be deleted. Ensure folders are completely empty before deleting them.", "alert", "OK", "bg-yellow-600", ()=>{});
            }

            successIds.forEach(id => selectedContexts.delete(id));
            updateContextBanner();
            await refreshTreeState();
        }
    );
}

// --- FORENSIX CHAT & REASONING PIPELINE ---
async function handleAsk() {
    const question = elements.questionInput.value.trim();
    if (!question) return;

    const model = elements.modelSelector.value;
    elements.questionInput.value = '';
    elements.questionInput.disabled = true;

    elements.askBtn.disabled = true;
    const originalBtnText = elements.askBtn.innerText;
    elements.askBtn.innerText = "⏳...";

    const targetFolderIds = Array.from(selectedContexts.keys());

    appendToChat('Detective', question, null);
    const loadingId = appendToChat('AI', 'Analyzing evidence vectors...', null);

    try {
        const data = await ApiService.askStructuredQuestion(question, targetFolderIds, elements.caseId.value, model);
        const msgId = "msg-" + Date.now();
        chatHistory[msgId] = {
            answer: data.answer || "No conclusion drawn.",
            reasoning: data.reasoning || "No specific evidence cited.",
            isValidated: false
        };

        updateChatBubble(loadingId, data.answer, msgId);
        selectMessage(msgId);
    } catch (error) {
        updateChatBubble(loadingId, "Connection lost to AI core.", null);
    } finally {
        elements.questionInput.disabled = false;
        elements.askBtn.disabled = false;
        elements.askBtn.innerText = originalBtnText;
        elements.questionInput.focus();
    }
}

function selectMessage(msgId) {
    if (!chatHistory[msgId]) return;
    activeMessageId = msgId;
    const msgData = chatHistory[msgId];

    document.querySelectorAll('.chat-bubble').forEach(el => el.classList.remove('msg-active'));
    document.getElementById(msgId).classList.add('msg-active');

    // SENIOR FIX: Securely render AI trace payload
    elements.reasonContainer.innerHTML = `
        <div class="font-mono text-blue-300 mb-2 border-b border-slate-600 pb-2">EVIDENCE TRACE:</div>
        <div class="leading-relaxed whitespace-pre-wrap">${escapeHTML(msgData.reasoning)}</div>
    `;

    if (msgData.isValidated) {
        elements.validBtn.disabled = true;
        elements.validBtn.innerText = "✓ Validated";
        swapClass(elements.validBtn, 'bg-slate-300', 'bg-green-500');
    } else {
        elements.validBtn.disabled = false;
        elements.validBtn.innerText = "Valid";
        swapClass(elements.validBtn, 'bg-green-500', 'bg-slate-300');
    }
}

function handleValidateReason() {
    if (!activeMessageId || !chatHistory[activeMessageId] || chatHistory[activeMessageId].isValidated) return;
    chatHistory[activeMessageId].isValidated = true;
    verifiedFacts.push(chatHistory[activeMessageId].reasoning);
    updateVerifiedCounter();
    selectMessage(activeMessageId);
}

function updateVerifiedCounter() {
    elements.verifiedCount.innerText = `${verifiedFacts.length} Verified Facts`;
    if (verifiedFacts.length > 0) {
        swapClass(elements.verifiedCount, 'bg-slate-400', 'bg-green-400');
    } else {
        swapClass(elements.verifiedCount, 'bg-green-400', 'bg-slate-400');
    }
}

async function handleGenerateReport() {
    if (verifiedFacts.length === 0) return CustomModal.show("Action Required", "You must validate at least one piece of evidence before generating a report.", "alert", "OK", "bg-blue-600", ()=>{});

    elements.generateReportBtn.disabled = true;
    elements.generateReportBtn.innerText = "Synthesizing...";
    elements.reportContainer.innerHTML = `<div class="animate-pulse text-blue-400 text-center mt-10">Cross-referencing verified facts to generate narrative...</div>`;

    try {
        const model = elements.modelSelector.value;
        const data = await ApiService.generateReport(verifiedFacts, elements.caseId.value, model);

        // SENIOR FIX: Securely render AI synthesis payload
        elements.reportContainer.innerHTML = `<div class="leading-relaxed">${escapeHTML(data.report)}</div>`;
    } catch (e) {
        elements.reportContainer.innerHTML = `<div class="text-red-400">Failed to generate report. Backend unavailable.</div>`;
    } finally {
        elements.generateReportBtn.disabled = false;
        elements.generateReportBtn.innerText = "Go (Generate)";
    }
}

function appendToChat(role, message, msgId) {
    const isAI = role === 'AI';
    const tempId = msgId || "temp-" + Date.now();
    const msgDiv = document.createElement('div');
    msgDiv.className = `flex ${isAI ? 'justify-start' : 'justify-end'}`;

    const clickHandler = isAI ? `onclick="selectMessage('${tempId}')" style="cursor: pointer;"` : "";
    const hoverFx = isAI ? "hover:border-blue-500 transition-colors" : "";

    // SENIOR FIX: Securely render raw text message inside chat bubble
    msgDiv.innerHTML = `
        <div id="${tempId}" ${clickHandler} class="chat-bubble max-w-[85%] rounded-lg px-4 py-2 border border-slate-700 ${isAI ? 'bg-slate-700 text-slate-200 ' + hoverFx : 'bg-blue-600 text-white border-blue-500'}">
            <span class="text-[10px] font-bold block mb-1 uppercase ${isAI ? 'text-blue-300' : 'text-blue-200'}">${role}</span>
            <span class="chat-text whitespace-pre-wrap text-sm">${escapeHTML(message)}</span>
        </div>
    `;
    elements.chatWindow.appendChild(msgDiv);
    elements.chatWindow.scrollTop = elements.chatWindow.scrollHeight;
    return tempId;
}

function updateChatBubble(elementId, newText, newRealId) {
    const el = document.getElementById(elementId);
    if (!el) return;
    if (newRealId) {
        el.id = newRealId;
        el.setAttribute('onclick', `selectMessage('${newRealId}')`);
    }
    el.querySelector('.chat-text').innerText = newText;
    elements.chatWindow.scrollTop = elements.chatWindow.scrollHeight;
}