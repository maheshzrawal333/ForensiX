/**
 * ============================================================================
 * ForensiX - Enterprise Human-In-The-Loop (HITL) UI Controller
 * ============================================================================
 * ARCHITECTURAL OVERVIEW:
 * This file acts as the primary orchestrator for the ForensiX investigation terminal.
 * It strictly separates transient frontend state (chat history, selected contexts)
 * from persistent backend state, ensuring that investigators have full control over
 * what the AI sees (Context Selection) and what is ultimately saved (Fact Validation).
 */

// ==========================================
// 1. GLOBAL STATE MANAGEMENT
// ==========================================
// This transient state is deliberately wiped clean every time the user switches
// investigative cases to mathematically prevent cross-case data contamination in the UI.

let chatHistory = {};           // Ledger mapping UI Message IDs to their raw AI JSON payloads
let activeMessageId = null;     // Tracks which chat bubble is currently selected for reasoning review
let verifiedFacts = [];         // The Human-In-The-Loop queue. Facts stored here will be synthesized into the final report.
let selectedContexts = new Map(); // Tracks selected checkboxes (Folders/Files) to scope the RAG Vector Search.

// SENIOR FIX #5: The Async Race Condition Preventer.
// If an investigator starts a 60-second folder expansion, but switches cases 5 seconds later,
// the original network request will eventually return and inject Folder A into Case B's UI.
// By tracking the "Generation", async callbacks can abort if the user has moved on.
let caseGeneration = 0;

// Centralized DOM Reference Dictionary to prevent repetitive document.getElementById calls
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

// ==========================================
// 2. UTILITY FUNCTIONS
// ==========================================

/**
 * Safely swaps a single CSS class on a DOM element.
 */
function swapClass(el, oldCls, newCls) {
    if (!el) return;
    if (oldCls) el.classList.remove(oldCls);
    if (newCls) el.classList.add(newCls);
}

/**
 * SECURITY BOUNDARY: XSS Prevention
 * The AI generates text that is injected directly into our DOM via innerHTML.
 * We must aggressively sanitize all HTML characters to prevent Stored XSS attacks
 * if the AI decides to hallucinate a <script> tag.
 */
function escapeHTML(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

// ==========================================
// 3. CUSTOM MODAL INFRASTRUCTURE
// ==========================================
/**
 * Replaces native browser alert()/prompt() which block the main thread and look unprofessional.
 * Implements strict accessibility guidelines (focus trapping, escape key to close).
 */
const CustomModal = {
    _timeoutId: null,
    _lastActiveElement: null,

    show: (title, message, type, confirmBtnText, confirmBtnColorClass, onConfirmCallback) => {
        if (CustomModal._timeoutId) clearTimeout(CustomModal._timeoutId);

        // SENIOR FIX #15: Accessibility Focus Management
        // Remember what the user was focused on before the modal hijacked the screen.
        CustomModal._lastActiveElement = document.activeElement;

        const overlay = document.getElementById('modalOverlay');
        const content = document.getElementById('modalContent');
        const input = document.getElementById('modalInput');
        const confirmBtn = document.getElementById('modalConfirm');

        document.getElementById('modalTitle').innerText = title;
        document.getElementById('modalBody').innerText = message;

        confirmBtn.className = `px-4 py-2 rounded text-white font-bold transition-colors shadow ${confirmBtnColorClass}`;
        confirmBtn.innerText = confirmBtnText;

        if (type === 'prompt') {
            input.classList.remove('hidden');
            input.value = '';
            setTimeout(() => input.focus(), 50); // Delay required for Tailwind transitions
        } else {
            input.classList.add('hidden');
            confirmBtn.focus();
        }

        overlay.classList.remove('hidden');
        overlay.classList.add('flex');
        setTimeout(() => swapClass(content, 'scale-95', 'scale-100'), 10);

        // Clone and replace the confirm button to instantly wipe any old event listeners attached to it.
        const newConfirmBtn = confirmBtn.cloneNode(true);
        confirmBtn.parentNode.replaceChild(newConfirmBtn, confirmBtn);

        newConfirmBtn.addEventListener('click', () => {
            const val = input.value;
            CustomModal.close();
            if(type === 'prompt') onConfirmCallback(val);
            else onConfirmCallback();
        });

        document.getElementById('modalCancel').onclick = CustomModal.close;

        // SENIOR FIX #15: UX Standard - Escape to close & Backdrop click to close
        const keyHandler = (e) => {
            if (e.key === 'Escape') {
                CustomModal.close();
                document.removeEventListener('keydown', keyHandler);
            }
        };
        document.addEventListener('keydown', keyHandler);

        overlay.onclick = (e) => {
            if (e.target === overlay) {
                CustomModal.close();
                overlay.onclick = null;
            }
        };
    },

    close: () => {
        if (CustomModal._timeoutId) clearTimeout(CustomModal._timeoutId);

        const overlay = document.getElementById('modalOverlay');
        const content = document.getElementById('modalContent');

        swapClass(content, 'scale-100', 'scale-95');

        CustomModal._timeoutId = setTimeout(() => {
            overlay.classList.remove('flex');
            overlay.classList.add('hidden');

            // Return focus to the original element to maintain keyboard navigation flow
            if (CustomModal._lastActiveElement) {
                CustomModal._lastActiveElement.focus();
                CustomModal._lastActiveElement = null;
            }
        }, 150); // Matches CSS transition duration
    }
};

// ==========================================
// 4. BOOTSTRAP & EVENT BINDING
// ==========================================
document.addEventListener("DOMContentLoaded", async () => {

    // RAG Pipeline Bindings
    elements.askBtn.addEventListener("click", handleAsk);
    // SENIOR FIX #12: Changed 'keypress' to 'keydown' for better cross-browser compatibility
    elements.questionInput.addEventListener("keydown", (e) => { if (e.key === 'Enter') handleAsk(); });
    elements.validBtn.addEventListener("click", handleValidateReason);
    elements.generateReportBtn.addEventListener("click", handleGenerateReport);

    // Structure Pipeline Bindings
    document.getElementById('refreshBtn').addEventListener("click", refreshTreeState);
    document.getElementById('newFolderBtn').addEventListener("click", handleCreateFolder);
    document.getElementById('newCaseBtn').addEventListener("click", handleCreateCase);

    // SENIOR FIX #7: Guarding case switches to prevent data loss.
    // If an investigator has validated facts but hasn't generated a report, switching cases
    // clears the state. We intercept the dropdown change, warn the user, and revert the UI
    // if they decline.
    let previousCaseValue = elements.caseId.value;
    elements.caseId.addEventListener("change", (e) => {
        const newCaseValue = e.target.value;
        if (verifiedFacts.length > 0) {
            CustomModal.show(
                "Unsaved Evidence",
                "You have validated facts that haven't been synthesized into a report. Switching cases will discard them. Proceed?",
                "confirm",
                "Discard & Switch",
                "bg-red-600 hover:bg-red-500",
                () => {
                    previousCaseValue = newCaseValue;
                    resetCaseContext();
                }
            );
            // Revert listeners
            document.getElementById('modalCancel').addEventListener('click', () => { e.target.value = previousCaseValue; }, { once: true });
            document.getElementById('modalOverlay').addEventListener('click', (ev) => { if(ev.target.id === 'modalOverlay') e.target.value = previousCaseValue; }, { once: true });
            document.addEventListener('keydown', (ev) => { if(ev.key === 'Escape') e.target.value = previousCaseValue; }, { once: true });
        } else {
            previousCaseValue = newCaseValue;
            resetCaseContext();
        }
    });

    document.getElementById('globalUploadBtn').addEventListener('click', handleGlobalUpload);
    document.getElementById('globalRenameBtn').addEventListener('click', handleGlobalRename);
    document.getElementById('globalDeleteBtn').addEventListener('click', handleGlobalDelete);
    document.getElementById('fileInput').addEventListener('change', window.handleUpload);

    // -----------------------------------------------------------
    // SENIOR FIX #8: EVENT DELEGATION
    // -----------------------------------------------------------
    // The file tree is highly dynamic; folders expand and collapse, generating new HTML.
    // Rather than attaching hundreds of individual click listeners to every new file row,
    // we attach ONE listener to the parent container and intercept clicks as they bubble up.
    elements.fileTreeContainer.addEventListener('click', (event) => {

        // Scenario 1: User clicked a context inclusion checkbox
        if (event.target.matches('.context-checkbox')) {
            const id = event.target.dataset.id;
            const name = event.target.dataset.name;
            const type = event.target.dataset.type;

            if (event.target.checked) {
                selectedContexts.set(id, { name: name, type: type });
            } else {
                selectedContexts.delete(id);
            }
            updateContextBanner(); // Update the RAG Context UI
            return;
        }

        // Scenario 2: User clicked to expand/collapse a folder
        const expanderBtn = event.target.closest('[data-role="expander"]');
        const folderRow = event.target.closest('[data-role="folder-row"]');

        if (expanderBtn || (folderRow && !event.target.matches('input[type="checkbox"]'))) {
            const targetId = expanderBtn ? expanderBtn.dataset.folderId : folderRow.dataset.folderId;
            toggleFolderExpander(targetId);
        }
    });

    // Keyboard Accessibility for the File Tree (Space/Enter to interact)
    elements.fileTreeContainer.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
            const fileRow = event.target.closest('[data-role="file-row"]');
            if (fileRow) {
                event.preventDefault();
                const cb = fileRow.querySelector('input');
                cb.checked = !cb.checked;
                // Dispatch click to trigger the delegation logic above
                cb.dispatchEvent(new MouseEvent('click', { bubbles: true }));
                return;
            }

            const folderRow = event.target.closest('[data-role="folder-row"]');
            if (folderRow) {
                event.preventDefault();
                toggleFolderExpander(folderRow.dataset.folderId);
            }
        }
    });

    // Initialize UI and Fetch remote data
    initViewControllers();
    await loadModels();
    await loadCases();
    resetCaseContext();
});

// ==========================================
// 5. VIEW CONTROLLERS (Responsive Panels)
// ==========================================
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

// ==========================================
// 6. CORE LOGIC & STATE WIPES
// ==========================================
async function loadModels() {
    const models = await ApiService.getModels();
    elements.modelSelector.innerHTML = models.map(m => `<option value="${escapeHTML(m)}">${escapeHTML(m)}</option>`).join('');
}

/**
 * Executes a complete wipe of the transient UI state.
 * Called immediately upon changing the Case ID dropdown to prevent cross-case data leaks.
 */
function resetCaseContext() {
    caseGeneration++; // SENIOR FIX #5: Increment generation to invalidate hanging async callbacks
    const myGen = caseGeneration;

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

    // Begin rebuilding the file tree for the newly selected case
    loadRootDirectory(myGen);
}

/**
 * Visually renders the selected checkboxes as tags above the chat window.
 * This ensures the investigator always knows exactly which folders the AI will search.
 */
function updateContextBanner() {
    const displayElement = document.getElementById('activeContextDisplay');
    if (selectedContexts.size === 0) {
        displayElement.innerHTML = `<span class="bg-slate-800 px-2 py-1 rounded text-blue-300 shadow-inner">/Root (Entire Case)</span>`;
    } else {
        let tagsHtml = Array.from(selectedContexts.values()).map(item => {
            const icon = item.type === 'folder' ? '📁' : getFileIcon(item.name);
            return `<span class="bg-blue-900/80 border border-blue-500 text-blue-100 px-2 py-0.5 rounded text-[11px] mr-1 mb-1 inline-flex items-center shadow-sm whitespace-nowrap">${icon} <span class="ml-1 truncate max-w-[150px]">${escapeHTML(item.name)}</span></span>`;
        }).join('');
        displayElement.innerHTML = `<div class="flex flex-wrap gap-1 w-full overflow-hidden">${tagsHtml}</div>`;
    }
}

async function loadCases(selectTargetId = null) {
    if (!elements.caseId) return;
    try {
        const cases = await ApiService.getTenants();
        window.activeCases = cases;

        if (cases.length === 0) {
            elements.caseId.innerHTML = `<option value="DEFAULT-CASE">No Cases Found</option>`;
            return;
        }

        elements.caseId.innerHTML = cases.map(c => `<option value="${escapeHTML(c.id)}">${escapeHTML(c.id)} - ${escapeHTML(c.name)}</option>`).join('');
        if (selectTargetId) elements.caseId.value = selectTargetId;
    } catch (e) {
        // SENIOR FIX #14: Distinguish an offline/unreachable backend from a 0-case state.
        console.error("Failed to load cases:", e);
        elements.caseId.innerHTML = `<option value="DEFAULT-CASE" class="text-red-500">Offline Mode (Local Default)</option>`;
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

            // SENIOR FIX #4: Generate UUIDs on the frontend to allow offline creation capabilities
            // and guarantee collision-resistant uniqueness.
            const caseId = `CASE-${crypto.randomUUID()}`;

            try {
                await ApiService.createTenant(caseId, caseName);
                await loadCases(caseId);
                resetCaseContext();
            } catch (e) {
                console.error("Create Case Failed:", e);
                CustomModal.show("Error", e.message || "Failed to create new case.", "alert", "OK", "bg-red-600", ()=>{});
            }
        }
    );
}

// ==========================================
// 7. FILE TREE & DIRECTORY LOGIC
// ==========================================

/**
 * Re-syncs the UI tree with the database without collapsing the folders the user had open.
 */
async function refreshTreeState() {
    // 1. Snapshot open folders
    const openFolders = Array.from(document.querySelectorAll('.tree-line:not(.hidden)'))
        .map(el => el.id.replace('children-', ''));

    const myGen = caseGeneration;
    await loadRootDirectory(myGen);

    // 2. Re-expand them sequentially
    for (const folderId of openFolders) {
        if (folderId === 'root') continue;
        const childrenContainer = document.getElementById(`children-${folderId}`);
        const expanderIcon = document.querySelector(`[data-role="expander"][data-folder-id="${folderId}"]`);

        if (childrenContainer && childrenContainer.classList.contains('hidden')) {
            childrenContainer.classList.remove('hidden');
            if (expanderIcon) expanderIcon.innerText = '▼';
            await expandFolder(folderId, childrenContainer, myGen);
        }
    }
}

async function loadRootDirectory(gen) {
    elements.fileTreeContainer.innerHTML = `
        <div data-role="folder-row" data-folder-id="root" class="tree-node p-1.5 rounded flex items-center gap-2 border border-transparent">
            <span class="text-slate-400">🗄️</span> 
            <span class="font-bold text-blue-300 cursor-default">Case Root</span>
        </div>
        <div id="children-root" class="tree-line">
            <div class="text-center text-slate-500 text-xs mt-2 animate-pulse">Loading Tree...</div>
        </div>
    `;
    await expandFolder("root", document.getElementById("children-root"), gen);
}

function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    if (['pdf'].includes(ext)) return '📕';
    if (['csv', 'xlsx', 'xls'].includes(ext)) return '📊';
    if (['png', 'jpg', 'jpeg'].includes(ext)) return '🖼️';
    if (['doc', 'docx', 'txt'].includes(ext)) return '📝';
    return '📄';
}

/**
 * Performs a network fetch to retrieve a single level of the adjacency list.
 * @param {string} folderId The UUID of the parent directory.
 * @param {HTMLElement} containerElement The DOM div to inject the children into.
 * @param {number} gen The current case generation (to abort on case swap).
 */
async function expandFolder(folderId, containerElement, gen = caseGeneration) {
    const caseId = elements.caseId.value;

    try {
        // Parallel fetching to minimize UI latency
        const [folders, files] = await Promise.all([
            ApiService.getFolders(folderId, caseId),
            ApiService.getFiles(folderId, caseId)
        ]);

        // SENIOR FIX #5: Check if the user swapped cases while we were fetching
        if (gen !== caseGeneration) return;

        containerElement.innerHTML = '';
        if (folders.length === 0 && files.length === 0) {
            containerElement.innerHTML = '<div class="text-slate-600 text-xs italic py-1">Empty</div>';
            return;
        }

        // We use data-attributes (data-role, data-id) so the Event Delegation
        // listener attached to the root container can handle interactions.
        folders.forEach(folder => {
            const isChecked = selectedContexts.has(folder.id) ? 'checked' : '';
            const fId = escapeHTML(folder.id);
            const fName = escapeHTML(folder.name);

            const folderHtml = `
                <div class="mt-1">
                    <div data-role="folder-row" data-folder-id="${fId}" tabindex="0" role="button" class="tree-node p-1 rounded flex items-center border border-transparent hover:bg-slate-700/50 focus:bg-slate-700/50 focus:outline-none transition-colors cursor-pointer">
                        <span data-role="expander" data-folder-id="${fId}" class="text-slate-400 hover:text-white px-1 w-4 text-center shrink-0">▶</span>
                        <input type="checkbox" ${isChecked} tabindex="-1" class="w-3 h-3 cursor-pointer shrink-0 accent-blue-500 mr-2 ml-1 context-checkbox" data-id="${fId}" data-name="${fName}" data-type="folder">
                        <span class="text-yellow-500 shrink-0 text-sm mr-1">📁</span> 
                        <span class="text-sm text-slate-200 select-none pr-4 truncate" title="${fName}">${fName}</span>
                    </div>
                    <div id="children-${fId}" class="tree-line hidden"></div>
                </div>
            `;
            containerElement.insertAdjacentHTML('beforeend', folderHtml);
        });

        files.forEach(file => {
            const isChecked = selectedContexts.has(file.id) ? 'checked' : '';
            const icon = getFileIcon(file.fileName);
            const fId = escapeHTML(file.id);
            const fName = escapeHTML(file.fileName);

            const fileHtml = `
                <div data-role="file-row" tabindex="0" role="button" class="py-1 px-2 flex items-center hover:bg-slate-700/30 focus:bg-slate-700/50 focus:outline-none rounded mt-1 transition-colors cursor-pointer">
                    <input type="checkbox" ${isChecked} tabindex="-1" class="w-3 h-3 cursor-pointer shrink-0 accent-blue-500 mr-2 ml-5 context-checkbox" data-id="${fId}" data-name="${fName}" data-type="file">
                    <span class="text-xs shrink-0 mr-1 opacity-90">${icon}</span>
                    <span class="text-xs select-text pr-4 break-words" title="${fName}">${fName}</span>
                </div>
            `;
            containerElement.insertAdjacentHTML('beforeend', fileHtml);
        });
    } catch (error) {
        console.error(`Failed to expand folder ${folderId}:`, error);
        if (gen === caseGeneration) {
            containerElement.innerHTML = `<div class="text-red-400 text-xs py-1">Error loading contents</div>`;
        }
    }
}

async function toggleFolderExpander(folderId) {
    const childrenContainer = document.getElementById(`children-${folderId}`);
    const expanderIcon = document.querySelector(`[data-role="expander"][data-folder-id="${folderId}"]`);

    if (!childrenContainer) return;

    if (childrenContainer.classList.contains('hidden')) {
        childrenContainer.classList.remove('hidden');
        if (expanderIcon) expanderIcon.innerText = '▼';
        // Lazy-load data only if the container is currently empty
        if (childrenContainer.innerHTML.trim() === '') await expandFolder(folderId, childrenContainer);
    } else {
        childrenContainer.classList.add('hidden');
        if (expanderIcon) expanderIcon.innerText = '▶';
    }
};

// ==========================================
// 8. FILE SYSTEM MUTATIONS (CRUD)
// ==========================================

async function handleCreateFolder() {
    let targetFolderId = "root";
    let targetFolderName = "Root";

    const selected = Array.from(selectedContexts.entries());

    // UX/Validation Rule: Must select exactly one target directory
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
                console.error("Folder creation failed:", e);
                CustomModal.show("Error", e.message || "Failed to create folder.", "alert", "OK", "bg-red-600", ()=>{});
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

    // Pass the state to the hidden file input handler
    window.targetUploadFolderId = targetFolderId;
    document.getElementById('fileInput').click();
}

/**
 * Handles the high-latency multipart upload and SSE (Server-Sent Events) pipeline.
 */
window.handleUpload = async (event) => {
    const files = event.target.files;
    if (files.length === 0) return;
    const targetFolderId = window.targetUploadFolderId || "root";

    const originalText = document.getElementById('activeContextDisplay').innerHTML;
    document.getElementById('activeContextDisplay').innerHTML = `<span class="text-yellow-400 font-bold bg-slate-800 px-2 py-1 rounded animate-pulse">Uploading ${files.length} items...</span>`;

    // SENIOR FIX #3: Batch Processing Resilience.
    // In a multi-file upload, if file 2 fails, we should not abort the loop and skip files 3, 4, and 5.
    // We collect the errors and present a single consolidated alert at the end.
    const failures = [];

    for (let i = 0; i < files.length; i++) {
        try {
            // Step 1: Initiate network upload (Binary to S3)
            const data = await ApiService.uploadEvidence(files[i], targetFolderId, elements.caseId.value);

            // Step 2: Subscribe to the Redis PubSub stream to await vectorization completion
            await new Promise((resolve, reject) => {
                const eventSource = new EventSource(`/api/jobs/${data.jobId}/stream`);

                const streamTimeout = setTimeout(() => {
                    eventSource.close();
                    reject(new Error("Stream timed out from backend."));
                }, 600000); // 10 min hard timeout for massive disk images

                eventSource.addEventListener('progress', (e) => {
                    if (e.data === 'Complete') {
                        clearTimeout(streamTimeout);
                        eventSource.close();
                        resolve();
                    }
                });

                // SENIOR FIX #3: Handle premature socket closure
                eventSource.onerror = () => {
                    clearTimeout(streamTimeout);
                    eventSource.close();
                    reject(new Error("Connection to ingestion stream lost before completion."));
                };
            });
        } catch (error) {
            console.error("Upload failed for", files[i].name, error);
            failures.push({ name: files[i].name, message: error.message || "Unknown error" });
        }
    }

    event.target.value = ''; // Reset input to allow re-upload of the same file
    document.getElementById('activeContextDisplay').innerHTML = originalText;
    await refreshTreeState();

    if (failures.length > 0) {
        const list = failures.map(f => `• ${escapeHTML(f.name)}: ${escapeHTML(f.message)}`).join('\n');
        CustomModal.show(
            failures.length === files.length ? "Upload Failed" : "Some Files Failed",
            `${failures.length} of ${files.length} file(s) could not be processed:\n\n${list}`,
            "alert", "OK", "bg-red-600", () => {}
        );
    }
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

                // Optimistically update local context cache without wiping the array
                selectedContexts.set(id, { name: newName, type: 'folder' });
                updateContextBanner();
                await refreshTreeState();
            } catch (e) {
                console.error("Rename failed:", e);
                CustomModal.show("Error", e.message || "Failed to rename folder.", "alert", "OK", "bg-red-600", ()=>{});
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
                    console.error("Delete failed for item:", id, e);
                    hasError = true;
                }
            }

            if (hasError) {
                CustomModal.show("Partial Success", "Some items could not be deleted. Ensure folders are completely empty before deleting them.", "alert", "OK", "bg-yellow-600", ()=>{});
            }

            // Purge deleted items from the local RAG context array
            successIds.forEach(id => selectedContexts.delete(id));
            updateContextBanner();
            await refreshTreeState();
        }
    );
}

// ==========================================
// 9. RAG CHAT & REASONING PIPELINE (HITL)
// ==========================================

async function handleAsk() {
    const question = elements.questionInput.value.trim();
    if (!question) return;

    const model = elements.modelSelector.value;

    // UI Lockout during inference
    elements.questionInput.value = '';
    elements.questionInput.disabled = true;
    elements.askBtn.disabled = true;
    const originalBtnText = elements.askBtn.innerText;
    elements.askBtn.innerText = "⏳...";

    // Pull the active scope from the UI state
    const targetFolderIds = Array.from(selectedContexts.keys());

    appendToChat('Detective', question, null);
    const loadingId = appendToChat('AI', 'Analyzing evidence vectors...', null);

    try {
        // Dispatch to pgvector and local LLM
        const data = await ApiService.askStructuredQuestion(question, targetFolderIds, elements.caseId.value, model);
        const msgId = "msg-" + Date.now();

        // Save the structured JSON response into the transient ledger
        chatHistory[msgId] = {
            question: question, // Keep track of the originating prompt for report synthesis
            answer: data.answer || "No conclusion drawn.",
            reasoning: data.reasoning || "No specific evidence cited.",
            isValidated: false
        };

        // Update UI
        updateChatBubble(loadingId, data.answer, msgId);
        selectMessage(msgId); // Automatically focus the reasoning panel on the new answer
    } catch (error) {
        console.error("AI Query Failed:", error);
        updateChatBubble(loadingId, "Connection lost to AI core: " + (error.message || "Unknown Error"), null);
    } finally {
        // Unlock UI
        elements.questionInput.disabled = false;
        elements.askBtn.disabled = false;
        elements.askBtn.innerText = originalBtnText;
        elements.questionInput.focus();
    }
}

/**
 * Triggers when a user clicks an AI chat bubble. Loads the underlying logical trace.
 */
function selectMessage(msgId) {
    if (!chatHistory[msgId]) return;
    activeMessageId = msgId;
    const msgData = chatHistory[msgId];

    // Visually highlight the selected message
    document.querySelectorAll('.chat-bubble').forEach(el => el.classList.remove('msg-active'));
    document.getElementById(msgId).classList.add('msg-active');

    // FIX 5: Apply aggressive CSS wrapping to the Evidence Trace panel to prevent layout explosions
    // if the LLM cites a massive Base64 string or uninterrupted hash.
    elements.reasonContainer.innerHTML = `
        <div class="font-mono text-blue-300 mb-2 border-b border-slate-600 pb-2">EVIDENCE TRACE:</div>
        <div class="leading-relaxed whitespace-pre-wrap min-w-0" style="word-break: break-word; overflow-wrap: anywhere;">${escapeHTML(msgData.reasoning)}</div>
    `;

    // Manage the Validation Button State
    if (msgData.isValidated) {
        elements.validBtn.disabled = false;
        elements.validBtn.innerText = "✓ Validated (Click to Remove)";
        swapClass(elements.validBtn, 'bg-slate-300', 'bg-green-500');
        swapClass(elements.validBtn, 'hover:bg-green-400', 'hover:bg-red-400');
    } else {
        elements.validBtn.disabled = false;
        elements.validBtn.innerText = "Valid";
        swapClass(elements.validBtn, 'bg-green-500', 'bg-slate-300');
        swapClass(elements.validBtn, 'hover:bg-red-400', 'hover:bg-green-400');
    }
}

/**
 * The core Human-In-The-Loop mechanism.
 * Pushes or pops an AI statement from the global "Verified Facts" queue.
 */
function handleValidateReason() {
    if (!activeMessageId || !chatHistory[activeMessageId]) return;

    const msgData = chatHistory[activeMessageId];

    // SENIOR FIX #6: Maintain Provenance Array.
    // We allow toggling to prevent a user from being locked in if they misclick.
    if (msgData.isValidated) {
        // Pop fact
        msgData.isValidated = false;
        verifiedFacts = verifiedFacts.filter(fact => fact.messageId !== activeMessageId);
    } else {
        // Push fact
        msgData.isValidated = true;
        verifiedFacts.push({
            messageId: activeMessageId,
            question: msgData.question,
            answer: msgData.answer,
            reasoning: msgData.reasoning,
            validatedAt: new Date().toISOString()
        });
    }

    updateVerifiedCounter();
    selectMessage(activeMessageId); // Re-render the button UI state
}

function updateVerifiedCounter() {
    elements.verifiedCount.innerText = `${verifiedFacts.length} Verified Facts`;
    if (verifiedFacts.length > 0) {
        swapClass(elements.verifiedCount, 'bg-slate-400', 'bg-green-400');
    } else {
        swapClass(elements.verifiedCount, 'bg-green-400', 'bg-slate-400');
    }
}

// ==========================================
// 10. NARRATIVE SYNTHESIS (Final Report)
// ==========================================

async function handleGenerateReport() {
    // Hard block: The system must not generate a report entirely from hallucinations.
    if (verifiedFacts.length === 0) return CustomModal.show("Action Required", "You must validate at least one piece of evidence before generating a report.", "alert", "OK", "bg-blue-600", ()=>{});

    elements.generateReportBtn.disabled = true;
    elements.generateReportBtn.innerText = "Synthesizing...";
    elements.reportContainer.innerHTML = `<div class="animate-pulse text-blue-400 text-center mt-10">Cross-referencing verified facts to generate narrative...</div>`;

    try {
        const model = elements.modelSelector.value;

        // Compile the facts into an instruction block
        const evidenceStrings = verifiedFacts.map(fact => `Q: ${fact.question} | A: ${fact.answer} | Cite: ${fact.reasoning}`);

        // Dispatch to backend
        const data = await ApiService.generateReport(evidenceStrings, elements.caseId.value, model);

        // FIX 6: Apply aggressive text wrapping to the final report output
        elements.reportContainer.innerHTML = `<div class="leading-relaxed whitespace-pre-wrap min-w-0" style="word-break: break-word; overflow-wrap: anywhere;">${escapeHTML(data.report)}</div>`;
    } catch (e) {
        console.error("Report generation failed:", e);
        elements.reportContainer.innerHTML = `<div class="text-red-400">Failed to generate report: ${escapeHTML(e.message)}</div>`;
    } finally {
        elements.generateReportBtn.disabled = false;
        elements.generateReportBtn.innerText = "Go (Generate)";
    }
}

// ==========================================
// 11. UI RENDERING ENGINES
// ==========================================

/**
 * Injects a new message bubble into the RAG Chat interface.
 * Implements WhatsApp-style layout mechanics (Self on Right, AI on Left).
 */
function appendToChat(role, message, msgId) {
    const isAI = role === 'AI';
    const tempId = msgId || "temp-" + Date.now();
    const msgDiv = document.createElement('div');

    // Row wrapper ensures the flex bubble aligns left or right against the bounds of the container
    msgDiv.className = `flex ${isAI ? 'justify-start' : 'justify-end'} w-full mb-3 px-1`;

    const clickHandler = isAI ? `onclick="selectMessage('${tempId}')" style="cursor: pointer;"` : "";

    // Generate an inline timestamp (e.g., "7:27 PM") for UI realism
    const timeString = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    // WhatsApp Dark Mode Styling Logic:
    // isAI (Receiver): Slate-700 background, rounded with a sharp top-left corner
    // Detective (Sender): WhatsApp teal (#005c4b), rounded with a sharp top-right corner
    const bubbleStyle = isAI
        ? "bg-slate-700 text-slate-200 rounded-2xl rounded-tl-sm border border-slate-600 hover:border-blue-400 transition-colors shadow-sm"
        : "bg-[#005c4b] text-[#e9edef] rounded-2xl rounded-tr-sm shadow-sm";

    const nameTag = isAI
        ? `<span class="text-[11px] font-bold text-[#53bdeb] mb-0.5 tracking-wide">AI CORE</span>`
        : ``;

    const timeTag = isAI
        ? `<span class="text-[10px] text-slate-400">${timeString}</span>`
        : `<span class="text-[10px] text-[#8696a0]">${timeString}</span><span class="text-[#53bdeb] text-xs ml-1 tracking-tighter leading-none">✓✓</span>`;

    // THE CORE CSS FIX:
    // 'w-fit' shrinks the box to perfectly hug the text (preventing massive empty bubbles for short messages).
    // 'max-w-[85%]' stops a long paragraph from stretching entirely across the screen, mimicking mobile chat UX.
    msgDiv.innerHTML = `
        <div id="${tempId}" ${clickHandler} class="chat-bubble flex flex-col w-fit max-w-[85%] px-3 pt-2 pb-1.5 ${bubbleStyle}">
            ${nameTag}
            <div class="chat-text text-[14px] leading-relaxed whitespace-pre-wrap" style="word-break: break-word; overflow-wrap: anywhere;">${escapeHTML(message)}</div>
            <div class="flex justify-end items-end gap-1 mt-1 h-3 shrink-0">
                ${timeTag}
            </div>
        </div>
    `;

    elements.chatWindow.appendChild(msgDiv);
    elements.chatWindow.scrollTop = elements.chatWindow.scrollHeight; // Auto-scroll to bottom
    return tempId;
}

/**
 * Updates a temporary "Loading" bubble with the final resolved network response.
 */
function updateChatBubble(elementId, newText, newRealId) {
    const el = document.getElementById(elementId);
    if (!el) return;

    // Once the message resolves, bind the click listener for the reasoning panel
    if (newRealId) {
        el.id = newRealId;
        el.setAttribute('onclick', `selectMessage('${newRealId}')`);
    }

    // Update the text while preserving the surrounding HTML (like timestamps and name tags)
    el.querySelector('.chat-text').innerText = newText;
    elements.chatWindow.scrollTop = elements.chatWindow.scrollHeight;
}