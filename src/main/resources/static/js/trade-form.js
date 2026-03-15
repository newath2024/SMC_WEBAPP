const MAX_SETUP_IMAGE_SIZE_BYTES = 1024 * 1024; // 1MB per file
const MAX_TRADE_CHART_IMPORT_SIZE_BYTES = 10 * 1024 * 1024; // 10MB per file
const MAX_TRADE_CHART_IMPORT_FILES = 5;
const MAX_TRADE_CHART_IMPORT_TOTAL_BYTES = 25 * 1024 * 1024; // 25MB total per analysis
let isSubmittingTradeForm = false;
let isAnalyzingTradeChart = false;
let initialStopLossManuallyEdited = false;
let selectedTradeChartImportFiles = [];
let latestTradeChartImportAnalysis = null;

function parseNum(value) {
    if (value === null || value === undefined || value === '') return null;
    const normalized = String(value).replace(',', '.').trim();
    const num = Number(normalized);
    return Number.isFinite(num) ? num : null;
}

function round2(value) {
    return Math.round(value * 100) / 100;
}

function updateVolumeHint() {
    const symbol = document.getElementById('symbol')?.value;
    const hint = document.getElementById('volumeHint');
    if (!hint) return;

    switch (symbol) {
        case 'BTCUSD':
            hint.textContent = 'Unit: BTC';
            break;
        case 'ETHUSD':
            hint.textContent = 'Unit: ETH';
            break;
        case 'XAUUSD':
        case 'EURUSD':
        case 'GBPUSD':
        case 'USDJPY':
            hint.textContent = 'Unit: lot';
            break;
        default:
            hint.textContent = 'Unit: lot';
            break;
    }
}

function calculateRMultiple(direction, result, entry, sl, exit) {
    if (!direction || entry === null || sl === null || exit === null) {
        return null;
    }

    if (result === 'LOSS') {
        return -1.0;
    }

    let risk = null;
    let reward = null;

    if (direction === 'BUY') {
        risk = entry - sl;
        reward = exit - entry;
    } else if (direction === 'SELL') {
        risk = sl - entry;
        reward = entry - exit;
    }

    if (risk === null || risk <= 0) {
        return null;
    }

    return round2(reward / risk);
}

function calculatePnLBySymbol(symbol, direction, entry, exit, size) {
    if (!symbol || !direction || entry === null || exit === null || size === null || size <= 0) {
        return null;
    }

    let priceDiff = null;

    if (direction === 'BUY') {
        priceDiff = exit - entry;
    } else if (direction === 'SELL') {
        priceDiff = entry - exit;
    } else {
        return null;
    }

    switch (symbol) {
        case 'XAUUSD':
            return round2((priceDiff / 0.01) * 1.0 * size);

        case 'EURUSD':
        case 'GBPUSD':
            return round2((priceDiff / 0.0001) * 10.0 * size);

        case 'USDJPY':
            if (exit <= 0) return null;
            return round2((priceDiff / 0.01) * (1000.0 / exit) * size);

        case 'BTCUSD':
        case 'ETHUSD':
            return round2(priceDiff * size);

        default:
            return null;
    }
}

function calculateLiveMetrics() {
    const form = getTradeForm();
    const symbol = document.getElementById('symbol')?.value;
    const direction = document.getElementById('direction')?.value;
    const result = document.getElementById('result')?.value;

    const entry = parseNum(document.getElementById('entryPrice')?.value);
    const initialStop = parseNum(document.getElementById('initialStopLoss')?.value);
    const sl = parseNum(document.getElementById('stopLoss')?.value);
    const exit = parseNum(document.getElementById('exitPrice')?.value);
    const size = parseNum(document.getElementById('positionSize')?.value);

    const pnlInput = document.getElementById('pnl');
    const rInput = document.getElementById('rMultiple');

    if (!pnlInput || !rInput) return;

    pnlInput.value = '';
    rInput.value = '';

    const riskStop = initialStop !== null ? initialStop : (form && !isEditTradePage(form) ? sl : null);
    const r = calculateRMultiple(direction, result, entry, riskStop, exit);
    if (r !== null) {
        rInput.value = r.toFixed(2);
    }

    const pnl = calculatePnLBySymbol(symbol, direction, entry, exit, size);
    if (pnl !== null) {
        pnlInput.value = pnl.toFixed(2);
    }
}

function formatFileSize(bytes) {
    if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(2) + 'MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(2) + 'KB';
    return bytes + 'B';
}

function validateSetupImages() {
    const input = document.getElementById('setupImagesInput')
        || document.querySelector('input[name="setupImages"]');
    const errorEl = document.getElementById('setupImagesError');
    const infoEl = document.getElementById('setupImagesInfo');
    if (!input || !errorEl) return true;

    const files = Array.from(input.files || []);
    if (infoEl) {
        if (files.length === 0) {
            infoEl.textContent = '';
        } else {
            const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
            const fileLines = files.map((file) => `${file.name}: ${formatFileSize(file.size)}`);
            infoEl.textContent = `Selected ${files.length} file(s) | Total: ${formatFileSize(totalBytes)} | ${fileLines.join(' | ')}`;
        }
    }

    const oversized = files.filter((file) => file.size > MAX_SETUP_IMAGE_SIZE_BYTES);

    if (oversized.length === 0) {
        input.setCustomValidity('');
        errorEl.textContent = '';
        errorEl.classList.add('d-none');
        return true;
    }

    const fileNames = oversized
        .map((file) => `${file.name} (${formatFileSize(file.size)})`)
        .join(', ');
    const message = `File too large. Max 1MB each. Oversized: ${fileNames}`;
    input.setCustomValidity(message);
    errorEl.textContent = message;
    errorEl.classList.remove('d-none');
    return false;
}

window.validateSetupImages = validateSetupImages;

function getTradeForm() {
    return document.getElementById('tradeForm');
}

function getTradeChartImportInput() {
    return document.getElementById('tradeChartImportImage');
}

function getTradeChartImportButton() {
    return document.getElementById('tradeChartImportButton');
}

function getTradeChartImportFiles() {
    return selectedTradeChartImportFiles.map((entry) => entry.file);
}

function getSetupImagesInput() {
    return document.getElementById('setupImagesInput')
        || document.querySelector('input[name="setupImages"]');
}

function isEditTradePage(form) {
    if (!form || !form.action) return false;
    return /\/trades\/[^/]+\/edit$/.test(form.action);
}

function getTradeIdFromEditAction(form) {
    if (!form || !form.action) return null;
    const matched = form.action.match(/\/trades\/([^/]+)\/edit$/);
    return matched ? matched[1] : null;
}

function showSetupImageError(message) {
    const errorEl = document.getElementById('setupImagesError');
    if (!errorEl) return;
    if (!message) {
        errorEl.textContent = '';
        errorEl.classList.add('d-none');
        return;
    }
    errorEl.textContent = message;
    errorEl.classList.remove('d-none');
}

function showSetupImageInfo(message) {
    const infoEl = document.getElementById('setupImagesInfo');
    if (!infoEl) return;
    infoEl.textContent = message || '';
}

function setTradeChartImportStatus(message, tone) {
    const statusEl = document.getElementById('tradeChartImportStatus');
    if (!statusEl) return;

    statusEl.className = 'alert mb-0';
    if (!message) {
        statusEl.classList.add('d-none');
        statusEl.textContent = '';
        return;
    }

    const resolvedTone = tone || 'info';
    statusEl.classList.add(`alert-${resolvedTone}`);
    statusEl.textContent = message;
}

function setTradeChartImportFilesInfo(message) {
    const infoEl = document.getElementById('tradeChartImportFilesInfo');
    if (!infoEl) return;
    infoEl.textContent = message || '';
}

function getTradeChartImportFileKey(file) {
    if (!file) return '';
    return [file.name || '', file.size || 0, file.lastModified || 0].join('::');
}

function getTradeChartImportRoleLabel(index) {
    switch (index) {
        case 0:
            return 'HTF';
        case 1:
            return 'LTF';
        case 2:
            return 'Result';
        default:
            return `Support ${index - 2}`;
    }
}

function renderTradeChartImportQueuedFiles() {
    const container = document.getElementById('tradeChartImportQueuedFiles');
    if (!container) return;

    if (selectedTradeChartImportFiles.length === 0) {
        container.innerHTML = '';
        container.classList.add('d-none');
        return;
    }

    container.classList.remove('d-none');
    container.innerHTML = selectedTradeChartImportFiles.map((entry, index) => `
        <div class="trade-ai-import-queued-file">
            <div class="trade-ai-import-queued-file-main">
                <span class="trade-ai-import-queued-file-index">${getTradeChartImportRoleLabel(index)}</span>
                <div class="trade-ai-import-queued-file-copy">
                    <strong>${entry.file.name}</strong>
                    <span>${formatFileSize(entry.file.size)}</span>
                </div>
            </div>
            <button class="btn btn-outline-secondary btn-sm trade-ai-import-remove-button" type="button" data-file-key="${entry.key}">
                Remove
            </button>
        </div>
    `).join('');
}

function updateTradeChartImportButtonState() {
    const button = getTradeChartImportButton();
    const input = getTradeChartImportInput();
    if (!button) return;

    if (!button.dataset.originalHtml) {
        button.dataset.originalHtml = button.innerHTML || 'Analyze Screenshots';
    }

    const permanentlyDisabled = !!input?.hasAttribute('data-permanently-disabled');
    button.disabled = permanentlyDisabled || isAnalyzingTradeChart || selectedTradeChartImportFiles.length === 0;
}

function refreshTradeChartImportQueueUi() {
    renderTradeChartImportQueuedFiles();
    updateTradeChartImportFilesInfo();
    updateTradeChartImportButtonState();
}

function queueTradeChartImportFiles(files) {
    const incomingFiles = Array.from(files || []);
    if (incomingFiles.length === 0) {
        return;
    }

    const remainingSlots = MAX_TRADE_CHART_IMPORT_FILES - selectedTradeChartImportFiles.length;
    if (remainingSlots <= 0) {
        throw new Error('You already added 5 screenshots. Remove one before adding another.');
    }

    const dedupedIncoming = incomingFiles.filter((file) => {
        const key = getTradeChartImportFileKey(file);
        return key && !selectedTradeChartImportFiles.some((entry) => entry.key === key);
    });

    if (dedupedIncoming.length === 0) {
        throw new Error('Those screenshots were already added.');
    }

    if (dedupedIncoming.length > remainingSlots) {
        throw new Error(`You can add ${remainingSlots} more screenshot(s) only.`);
    }

    validateTradeChartImportFiles([
        ...selectedTradeChartImportFiles.map((entry) => entry.file),
        ...dedupedIncoming
    ]);

    dedupedIncoming.forEach((file) => {
        selectedTradeChartImportFiles.push({
            key: getTradeChartImportFileKey(file),
            file
        });
    });
}

function removeTradeChartImportFile(fileKey) {
    selectedTradeChartImportFiles = selectedTradeChartImportFiles.filter((entry) => entry.key !== fileKey);
    refreshTradeChartImportQueueUi();
    resetTradeChartImportPreview();
}

function setTradeChartImportSummaryVisible(visible) {
    const summaryEl = document.getElementById('tradeChartImportSummary');
    if (!summaryEl) return;
    summaryEl.classList.toggle('d-none', !visible);
}

function setTradeChartImportReviewActionsVisible(visible) {
    const actionsEl = document.getElementById('tradeChartImportReviewActions');
    if (!actionsEl) return;
    actionsEl.classList.toggle('d-none', !visible);
}

function setTradeChartImportSetupSuggestionVisible(visible) {
    const suggestionEl = document.getElementById('tradeChartImportSetupSuggestion');
    if (!suggestionEl) return;
    suggestionEl.classList.toggle('d-none', !visible);
}

function setTradeChartImportBusy(isBusy) {
    const button = getTradeChartImportButton();
    const input = getTradeChartImportInput();

    isAnalyzingTradeChart = isBusy;
    if (input) {
        input.disabled = isBusy || input.hasAttribute('data-permanently-disabled');
    }
    if (button) {
        if (!button.dataset.originalHtml) {
            button.dataset.originalHtml = button.innerHTML || 'Analyze Screenshots';
        }
        button.innerHTML = isBusy ? 'Analyzing Screenshots...' : button.dataset.originalHtml;
    }

    updateTradeChartImportButtonState();
}

function resetTradeChartImportPreview() {
    latestTradeChartImportAnalysis = null;
    setTradeChartImportStatus('', 'info');
    setTradeChartImportSummaryVisible(false);
    setTradeChartImportReviewActionsVisible(false);
    setTradeChartImportSetupSuggestionVisible(false);
}

function updateTradeChartImportFilesInfo() {
    const files = getTradeChartImportFiles();
    if (files.length === 0) {
        setTradeChartImportFilesInfo('No screenshots added yet. Choose one to start building the import set.');
        return;
    }

    const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
    const names = files.map((file, index) => `${getTradeChartImportRoleLabel(index)}: ${file.name} (${formatFileSize(file.size)})`);
    setTradeChartImportFilesInfo(`Added ${files.length}/${MAX_TRADE_CHART_IMPORT_FILES} screenshot(s) | Total: ${formatFileSize(totalBytes)} | ${names.join(' | ')}`);
}

function formatImportedPrice(value) {
    if (value === null || value === undefined || value === '') {
        return '-';
    }
    const num = Number(value);
    if (!Number.isFinite(num)) {
        return '-';
    }
    return num.toFixed(5).replace(/0+$/, '').replace(/\.$/, '');
}

function formatImportedValue(value) {
    if (value === null || value === undefined) {
        return '-';
    }
    const text = String(value).trim();
    return text ? text : '-';
}

function formatExitReason(value) {
    if (value === null || value === undefined) {
        return '-';
    }

    const text = String(value).trim();
    if (!text) {
        return '-';
    }

    return text.replaceAll('_', ' ');
}

function renderTradeChartImportSetupResolution(analysis) {
    const matchedNameEl = document.getElementById('tradeChartImportMatchedSetupName');
    const matchedConfidenceEl = document.getElementById('tradeChartImportMatchedSetupConfidence');
    const suggestedNameEl = document.getElementById('tradeChartImportSuggestedSetupName');
    const suggestedDescriptionEl = document.getElementById('tradeChartImportSuggestedSetupDescription');

    if (matchedNameEl) {
        matchedNameEl.textContent = formatImportedValue(analysis.matchedSetupName);
    }
    if (matchedConfidenceEl) {
        matchedConfidenceEl.textContent = formatImportedValue(analysis.matchedSetupConfidence);
    }
    if (suggestedNameEl) {
        suggestedNameEl.textContent = formatImportedValue(analysis.newSetupSuggestedName);
    }
    if (suggestedDescriptionEl) {
        suggestedDescriptionEl.textContent = analysis.newSetupSuggestedDescription
            ? analysis.newSetupSuggestedDescription
            : 'Create a setup from the AI suggestion only if it matches how you actually classify this trade.';
    }

    setTradeChartImportSetupSuggestionVisible(!analysis.matchedSetupId && !!analysis.newSetupSuggestedName);
}

function renderTradeChartImportSummary(analysis) {
    const mappings = [
        ['tradeChartImportSymbol', formatImportedValue(analysis.symbol)],
        ['tradeChartImportDirection', formatImportedValue(analysis.direction)],
        ['tradeChartImportTimeframeHTF', formatImportedValue(analysis.timeframeHTF)],
        ['tradeChartImportTimeframeLTF', formatImportedValue(analysis.timeframeLTF)],
        ['tradeChartImportTimeframeResult', formatImportedValue(analysis.timeframeResult)],
        ['tradeChartImportEntry', formatImportedPrice(analysis.entryPrice)],
        ['tradeChartImportStopLoss', formatImportedPrice(analysis.stopLoss)],
        ['tradeChartImportTakeProfit', formatImportedPrice(analysis.takeProfit)],
        ['tradeChartImportResult', formatImportedValue(analysis.result)],
        ['tradeChartImportExitPrice', formatImportedPrice(analysis.exitPrice)],
        ['tradeChartImportExitReason', formatExitReason(analysis.exitReason)],
        ['tradeChartImportEntrySource', formatImportedValue(analysis.entrySource)],
        ['tradeChartImportStopLossSource', formatImportedValue(analysis.stopLossSource)],
        ['tradeChartImportTakeProfitSource', formatImportedValue(analysis.takeProfitSource)],
        ['tradeChartImportSessionGuess', formatImportedValue(analysis.sessionGuess)],
        ['tradeChartImportSessionConfidence', formatImportedValue(analysis.sessionConfidence)],
        ['tradeChartImportEstimatedResultCandlesHeld', formatImportedValue(analysis.estimatedResultCandlesHeld)],
        ['tradeChartImportEstimatedHoldingMinutes', formatImportedValue(analysis.estimatedHoldingMinutes)],
        ['tradeChartImportConfidence', formatImportedValue(analysis.confidence)],
        ['tradeChartImportHtfBias', formatImportedValue(analysis.htfBias)],
        ['tradeChartImportHtfStructure', formatImportedValue(analysis.htfStructure)],
        ['tradeChartImportLtfTrigger', formatImportedValue(analysis.ltfTrigger)],
        ['tradeChartImportSetupGuess', formatImportedValue(analysis.setupGuess)],
        ['tradeChartImportTradeIdea', formatImportedValue(analysis.tradeIdea)]
    ];

    mappings.forEach(([id, value]) => {
        const el = document.getElementById(id);
        if (el) {
            el.textContent = value;
        }
    });

    renderTradeChartImportSetupResolution(analysis);
    setTradeChartImportSummaryVisible(true);
    setTradeChartImportReviewActionsVisible(true);
}

function ensureSelectOption(select, value) {
    if (!select || !value) return;

    const normalized = String(value).trim();
    if (!normalized) return;

    const existingOption = Array.from(select.options || []).find((option) => option.value === normalized);
    if (existingOption) {
        select.value = normalized;
        return;
    }

    const option = document.createElement('option');
    option.value = normalized;
    option.textContent = normalized;
    select.appendChild(option);
    select.value = normalized;
}

function toTradeSessionValue(sessionGuess) {
    if (!sessionGuess) return '';

    const normalized = String(sessionGuess).trim().toLowerCase();
    if (!normalized) return '';
    if (normalized.includes('new york') || normalized === 'ny') return 'NEW_YORK';
    if (normalized.includes('london')) return 'LONDON';
    if (normalized.includes('asia') || normalized.includes('tokyo') || normalized.includes('sydney')) return 'ASIA';
    if (normalized.includes('other')) return 'OTHER';
    return '';
}

function timeframeToMinutes(timeframe) {
    if (!timeframe) return null;
    switch (String(timeframe).trim().toUpperCase()) {
        case 'M1':
            return 1;
        case 'M3':
            return 3;
        case 'M5':
            return 5;
        case 'M15':
            return 15;
        case 'M30':
            return 30;
        case 'H1':
            return 60;
        case 'H4':
            return 240;
        default:
            return null;
    }
}

function trySelectSetupByName(setupName) {
    if (!setupName) return false;
    const setupSelect = document.getElementById('setupId');
    if (!setupSelect) return false;

    const normalized = setupName.trim().toLowerCase();
    if (!normalized) return false;

    const matchedOption = Array.from(setupSelect.options || []).find((option) => {
        const optionText = option.textContent ? option.textContent.trim().toLowerCase() : '';
        return option.value && optionText === normalized;
    });

    if (!matchedOption) {
        return false;
    }

    setupSelect.value = matchedOption.value;
    return true;
}

function trySelectSetupById(setupId) {
    if (!setupId) return false;
    const setupSelect = document.getElementById('setupId');
    if (!setupSelect) return false;

    const matchedOption = Array.from(setupSelect.options || []).find((option) => option.value === setupId);
    if (!matchedOption) {
        return false;
    }

    setupSelect.value = matchedOption.value;
    return true;
}

function normalizeImportedResultForForm(result) {
    if (!result) return '';
    const normalized = String(result).trim().toUpperCase();
    switch (normalized) {
        case 'WIN':
        case 'LOSS':
        case 'BREAKEVEN':
        case 'PARTIAL_WIN':
            return normalized;
        case 'BE':
            return 'BREAKEVEN';
        default:
            return '';
    }
}

function buildTradeChartImportNote(analysis) {
    const fragments = ['Imported from TradingView screenshots'];
    if (analysis.timeframeHTF) {
        fragments.push(`HTF: ${analysis.timeframeHTF}`);
    }
    if (analysis.timeframeLTF) {
        fragments.push(`LTF: ${analysis.timeframeLTF}`);
    }
    if (analysis.timeframeResult) {
        fragments.push(`Result TF: ${analysis.timeframeResult}`);
    }
    if (analysis.sessionGuess) {
        const sessionDetail = analysis.sessionConfidence
            ? `${analysis.sessionGuess} (${analysis.sessionConfidence})`
            : analysis.sessionGuess;
        fragments.push(`Session: ${sessionDetail}`);
    }
    if (analysis.estimatedResultCandlesHeld !== null && analysis.estimatedResultCandlesHeld !== undefined) {
        fragments.push(`Estimated result candles held: ${analysis.estimatedResultCandlesHeld}`);
    }
    if (analysis.estimatedHoldingMinutes !== null && analysis.estimatedHoldingMinutes !== undefined) {
        fragments.push(`Estimated holding minutes: ${analysis.estimatedHoldingMinutes}`);
    }
    if (analysis.result) {
        fragments.push(`Result: ${analysis.result}`);
    }
    if (analysis.exitReason) {
        fragments.push(`Exit reason: ${analysis.exitReason}`);
    }
    if (analysis.exitPrice !== null && analysis.exitPrice !== undefined) {
        fragments.push(`Exit price: ${formatImportedPrice(analysis.exitPrice)}`);
    }
    if (analysis.htfBias) {
        fragments.push(`HTF bias: ${analysis.htfBias}`);
    }
    if (analysis.htfStructure) {
        fragments.push(`HTF structure: ${analysis.htfStructure}`);
    }
    if (analysis.ltfTrigger) {
        fragments.push(`LTF trigger: ${analysis.ltfTrigger}`);
    }
    if (analysis.entrySource) {
        fragments.push(`Entry source: ${analysis.entrySource}`);
    }
    if (analysis.stopLossSource) {
        fragments.push(`Stop loss source: ${analysis.stopLossSource}`);
    }
    if (analysis.takeProfitSource) {
        fragments.push(`Take profit source: ${analysis.takeProfitSource}`);
    }
    if (analysis.setupGuess) {
        fragments.push(`Setup guess: ${analysis.setupGuess}`);
    }
    if (analysis.matchedSetupName) {
        const matchDetail = analysis.matchedSetupConfidence
            ? `${analysis.matchedSetupName} (${analysis.matchedSetupConfidence})`
            : analysis.matchedSetupName;
        fragments.push(`Matched setup: ${matchDetail}`);
    } else if (analysis.newSetupSuggestedName) {
        fragments.push(`Suggested setup: ${analysis.newSetupSuggestedName}`);
    }
    if (analysis.confidence) {
        fragments.push(`Confidence: ${analysis.confidence}`);
    }
    return fragments.join(' | ');
}

function mergeTradeChartImportNote(analysis) {
    const noteInput = document.getElementById('tradeNote') || document.querySelector('textarea[name="note"]');
    if (!noteInput) return;

    const importNote = buildTradeChartImportNote(analysis);
    const existingLines = String(noteInput.value || '')
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line
            && !line.startsWith('Imported from TradingView screenshot')
            && !line.startsWith('Imported from TradingView screenshots')
            && !line.startsWith('Trade idea: '));

    const noteLines = [importNote];
    if (analysis.tradeIdea) {
        noteLines.push(`Trade idea: ${analysis.tradeIdea}`);
    }

    noteInput.value = [...noteLines, ...existingLines].join('\n');
}

function applyTradeChartAnalysis(analysis) {
    if (!analysis) return;

    const symbolSelect = document.getElementById('symbol');
    const directionSelect = document.getElementById('direction');
    const resultSelect = document.getElementById('result');
    const ltfSelect = document.getElementById('ltf');
    const htfSelect = document.getElementById('htf');
    const sessionSelect = document.getElementById('session');
    const estimatedHoldingInput = document.getElementById('estimatedHoldingMinutes');
    const estimatedLtfCandlesInput = document.getElementById('estimatedLtfCandlesHeld');
    const sessionGuessInput = document.getElementById('sessionGuess');
    const sessionConfidenceInput = document.getElementById('sessionConfidence');
    const entryInput = document.getElementById('entryPrice');
    const stopLossInput = document.getElementById('stopLoss');
    const initialStopLossInput = document.getElementById('initialStopLoss');
    const takeProfitInput = document.querySelector('input[name="takeProfit"]');
    const exitPriceInput = document.getElementById('exitPrice');

    if (analysis.symbol && symbolSelect) {
        ensureSelectOption(symbolSelect, analysis.symbol);
    }

    if (analysis.direction && directionSelect) {
        ensureSelectOption(directionSelect, analysis.direction);
    }

    const importedResult = normalizeImportedResultForForm(analysis.result);
    if (importedResult && resultSelect) {
        ensureSelectOption(resultSelect, importedResult);
    }

    if (analysis.entryPrice !== null && analysis.entryPrice !== undefined && entryInput) {
        entryInput.value = analysis.entryPrice;
    }

    if (analysis.stopLoss !== null && analysis.stopLoss !== undefined) {
        if (stopLossInput) {
            stopLossInput.value = analysis.stopLoss;
        }
        if (initialStopLossInput) {
            initialStopLossInput.value = analysis.stopLoss;
            initialStopLossManuallyEdited = true;
        }
    }

    if (analysis.takeProfit !== null && analysis.takeProfit !== undefined && takeProfitInput) {
        takeProfitInput.value = analysis.takeProfit;
    }

    if (analysis.exitPrice !== null && analysis.exitPrice !== undefined && exitPriceInput) {
        exitPriceInput.value = analysis.exitPrice;
    }

    if (analysis.timeframeLTF && ltfSelect) {
        ensureSelectOption(ltfSelect, analysis.timeframeLTF);
    }

    if (analysis.timeframeHTF && htfSelect) {
        ensureSelectOption(htfSelect, analysis.timeframeHTF);
    }

    if (estimatedHoldingInput) {
        estimatedHoldingInput.value = analysis.estimatedHoldingMinutes ?? '';
    }

    if (estimatedLtfCandlesInput) {
        let estimatedLtfCandles = '';
        const ltfMinutes = timeframeToMinutes(analysis.timeframeLTF);
        if (analysis.estimatedHoldingMinutes !== null && analysis.estimatedHoldingMinutes !== undefined && ltfMinutes) {
            estimatedLtfCandles = Math.round(Number(analysis.estimatedHoldingMinutes) / ltfMinutes);
        }
        estimatedLtfCandlesInput.value = estimatedLtfCandles;
    }

    if (sessionGuessInput) {
        sessionGuessInput.value = analysis.sessionGuess || '';
    }

    if (sessionConfidenceInput) {
        sessionConfidenceInput.value = analysis.sessionConfidence || '';
    }

    if (!document.getElementById('entryTime')?.value && analysis.sessionGuess && sessionSelect && !sessionSelect.value) {
        const sessionValue = toTradeSessionValue(analysis.sessionGuess);
        if (sessionValue) {
            sessionSelect.value = sessionValue;
        }
    }

    if (analysis.matchedSetupId) {
        trySelectSetupById(analysis.matchedSetupId);
    } else if (analysis.matchedSetupName) {
        trySelectSetupByName(analysis.matchedSetupName);
    } else if (analysis.setupGuess) {
        trySelectSetupByName(analysis.setupGuess);
    }

    mergeTradeChartImportNote(analysis);
    updateVolumeHint();
    calculateLiveMetrics();
}

async function createSetupFromSuggestion(name, description) {
    const formData = new FormData();
    formData.append('name', name);
    if (description) {
        formData.append('description', description);
    }

    const response = await fetch('/api/trades/import/setup-suggestion', {
        method: 'POST',
        body: formData,
        credentials: 'same-origin'
    });

    let payload = null;
    try {
        payload = await response.json();
    } catch (ignored) {
    }

    if (!response.ok) {
        const message = payload && payload.message
            ? payload.message
            : 'Could not create setup from suggestion.';
        throw new Error(message);
    }

    return payload;
}

function validateTradeChartImportFiles(files) {
    if (!files || files.length === 0) {
        throw new Error('Choose between 1 and 5 TradingView screenshots first.');
    }
    if (files.length > MAX_TRADE_CHART_IMPORT_FILES) {
        throw new Error('Choose up to 5 TradingView screenshots at a time.');
    }

    const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
    if (totalBytes > MAX_TRADE_CHART_IMPORT_TOTAL_BYTES) {
        throw new Error('Selected TradingView screenshots are too large together. Keep the total upload size at 25MB or below.');
    }

    const invalidType = files.find((file) => !file.type || !file.type.startsWith('image/'));
    if (invalidType) {
        throw new Error('Only image files are supported for TradingView screenshot import.');
    }

    const oversized = files.find((file) => file.size > MAX_TRADE_CHART_IMPORT_SIZE_BYTES);
    if (oversized) {
        throw new Error(`Each TradingView screenshot must be 10MB or smaller. Oversized: ${oversized.name}`);
    }
}

async function analyzeTradeChartImages(files) {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));

    const response = await fetch('/api/trades/import/tradingview-image', {
        method: 'POST',
        body: formData,
        credentials: 'same-origin'
    });

    let payload = null;
    try {
        payload = await response.json();
    } catch (ignored) {
    }

    if (!response.ok) {
        const message = payload && payload.message
            ? payload.message
            : 'TradingView screenshot analysis failed.';
        throw new Error(message);
    }

    return payload;
}

async function handleTradeChartImport() {
    if (isAnalyzingTradeChart) {
        return;
    }

    const input = getTradeChartImportInput();
    if (!input || input.disabled) {
        return;
    }

    const files = getTradeChartImportFiles();

    try {
        validateTradeChartImportFiles(files);
        setTradeChartImportBusy(true);
        setTradeChartImportStatus(`Analyzing ${files.length} TradingView screenshot(s)...`, 'info');

        const payload = await analyzeTradeChartImages(files);
        const analysis = payload && payload.analysis ? payload.analysis : null;
        const imageCount = payload && payload.imageCount ? payload.imageCount : files.length;
        if (!analysis) {
            throw new Error('TradingView screenshot analysis returned no data.');
        }

        latestTradeChartImportAnalysis = analysis;
        renderTradeChartImportSummary(analysis);
        applyTradeChartAnalysis(analysis);
        setTradeChartImportStatus(`${imageCount} screenshot(s) analyzed together. The combined values were added to the form below. Review them, then click Save Trade.`, 'success');
    } catch (error) {
        latestTradeChartImportAnalysis = null;
        setTradeChartImportSummaryVisible(false);
        setTradeChartImportReviewActionsVisible(false);
        setTradeChartImportSetupSuggestionVisible(false);
        setTradeChartImportStatus(error.message || 'TradingView screenshot analysis failed.', 'danger');
    } finally {
        setTradeChartImportBusy(false);
    }
}

function setSubmitButtonState(disabled, textOverride) {
    const form = getTradeForm();
    if (!form) return;
    const submitButton = form.querySelector('button[type="submit"]');
    if (!submitButton) return;

    if (!submitButton.dataset.originalText) {
        submitButton.dataset.originalText = submitButton.textContent || '';
    }

    submitButton.disabled = disabled;
    submitButton.textContent = disabled
        ? (textOverride || 'Processing...')
        : submitButton.dataset.originalText;
}

async function uploadSetupImages(tradeId, files, imageType) {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));
    if (imageType) {
        formData.append('imageType', imageType);
    }

    const response = await fetch(`/api/trades/${tradeId}/images`, {
        method: 'POST',
        body: formData,
        credentials: 'same-origin'
    });

    let payload = null;
    try {
        payload = await response.json();
    } catch (ignored) {
    }

    if (!response.ok) {
        const message = payload && payload.message
            ? payload.message
            : 'Image upload failed';
        throw new Error(message);
    }

    return payload;
}

async function deleteTradeImage(tradeId, imageId) {
    const response = await fetch(`/api/trades/${tradeId}/images/${imageId}`, {
        method: 'DELETE',
        credentials: 'same-origin'
    });

    let payload = null;
    try {
        payload = await response.json();
    } catch (ignored) {
    }

    if (!response.ok) {
        const message = payload && payload.message
            ? payload.message
            : 'Image delete failed';
        throw new Error(message);
    }
}

async function handleTradeFormSubmit(event) {
    if (isSubmittingTradeForm) {
        return;
    }

    const form = getTradeForm();
    const setupImagesInput = getSetupImagesInput();
    if (!form || !setupImagesInput) {
        return;
    }

    if (!validateSetupImages()) {
        event.preventDefault();
        return;
    }

    const files = Array.from(setupImagesInput.files || []);
    const imageTypeSelect = document.getElementById('setupImageType');
    const imageType = imageTypeSelect ? imageTypeSelect.value : null;
    if (files.length === 0) {
        return;
    }

    event.preventDefault();

    if (!isEditTradePage(form)) {
        showSetupImageError('Save trade first, then upload setup images in Edit Trade.');
        return;
    }

    const tradeId = getTradeIdFromEditAction(form);
    if (!tradeId) {
        showSetupImageError('Cannot determine trade id for image upload.');
        return;
    }

    try {
        isSubmittingTradeForm = true;
        setSubmitButtonState(true, 'Uploading images...');
        showSetupImageError('');
        await uploadSetupImages(tradeId, files, imageType);
        setupImagesInput.value = '';
        showSetupImageInfo('Images uploaded successfully. Saving trade...');
        form.submit();
    } catch (error) {
        showSetupImageError(error.message || 'Image upload failed');
    } finally {
        isSubmittingTradeForm = false;
        setSubmitButtonState(false);
    }
}

function initTradeFormPage() {
    const watchedIds = [
        'symbol',
        'direction',
        'result',
        'entryPrice',
        'stopLoss',
        'exitPrice',
        'positionSize'
    ];

    watchedIds.forEach((id) => {
        const el = document.getElementById(id);
        if (el) {
            el.addEventListener('input', function () {
                updateVolumeHint();
                calculateLiveMetrics();
            });
            el.addEventListener('change', function () {
                updateVolumeHint();
                calculateLiveMetrics();
            });
        }
    });

    const setupImagesInput = getSetupImagesInput();
    const tradeChartImportInput = getTradeChartImportInput();
    const tradeChartImportButton = getTradeChartImportButton();
    const initialStopLossInput = document.getElementById('initialStopLoss');
    const stopLossInput = document.getElementById('stopLoss');
    if (setupImagesInput) {
        setupImagesInput.addEventListener('change', validateSetupImages);
    }

    if (tradeChartImportButton) {
        tradeChartImportButton.addEventListener('click', handleTradeChartImport);
    }

    if (tradeChartImportInput) {
        tradeChartImportInput.addEventListener('change', function () {
            try {
                queueTradeChartImportFiles(tradeChartImportInput.files);
                refreshTradeChartImportQueueUi();
                resetTradeChartImportPreview();
                setTradeChartImportStatus(`${selectedTradeChartImportFiles.length} screenshot(s) ready. Add more or click Analyze Screenshots.`, 'info');
            } catch (error) {
                setTradeChartImportStatus(error.message || 'Could not add selected screenshots.', 'danger');
            } finally {
                tradeChartImportInput.value = '';
            }
        });
    }

    if (tradeChartImportInput && tradeChartImportInput.disabled) {
        tradeChartImportInput.setAttribute('data-permanently-disabled', 'true');
    }

    const form = getTradeForm();
    if (form) {
        form.addEventListener('submit', handleTradeFormSubmit);
    }

    if (initialStopLossInput) {
        initialStopLossInput.addEventListener('input', function () {
            initialStopLossManuallyEdited = true;
            calculateLiveMetrics();
        });
        initialStopLossInput.addEventListener('change', function () {
            initialStopLossManuallyEdited = true;
            calculateLiveMetrics();
        });
    }

    if (stopLossInput && initialStopLossInput && form && !isEditTradePage(form)) {
        const syncInitialStopLoss = function () {
            if (initialStopLossManuallyEdited) {
                return;
            }
            initialStopLossInput.value = stopLossInput.value || '';
            calculateLiveMetrics();
        };

        stopLossInput.addEventListener('input', syncInitialStopLoss);
        stopLossInput.addEventListener('change', syncInitialStopLoss);

        if (!initialStopLossInput.value && stopLossInput.value) {
            initialStopLossInput.value = stopLossInput.value;
        }
    }

    document.addEventListener('click', async function (event) {
        const removeImportFileButton = event.target.closest('.trade-ai-import-remove-button');
        if (removeImportFileButton) {
            const fileKey = removeImportFileButton.getAttribute('data-file-key');
            if (fileKey) {
                removeTradeChartImportFile(fileKey);
            }
            return;
        }

        const createSetupButton = event.target.closest('#tradeChartImportCreateSetupButton');
        if (createSetupButton) {
            if (!latestTradeChartImportAnalysis || !latestTradeChartImportAnalysis.newSetupSuggestedName) {
                setTradeChartImportStatus('No setup suggestion is available to create.', 'warning');
                return;
            }

            createSetupButton.disabled = true;
            try {
                const payload = await createSetupFromSuggestion(
                    latestTradeChartImportAnalysis.newSetupSuggestedName,
                    latestTradeChartImportAnalysis.newSetupSuggestedDescription
                );

                const setupSelect = document.getElementById('setupId');
                if (setupSelect && payload && payload.setupId && payload.setupName) {
                    let option = Array.from(setupSelect.options || []).find((candidate) => candidate.value === payload.setupId);
                    if (!option) {
                        option = document.createElement('option');
                        option.value = payload.setupId;
                        option.textContent = payload.setupName;
                        setupSelect.appendChild(option);
                    }
                    setupSelect.value = payload.setupId;
                }

                latestTradeChartImportAnalysis = {
                    ...latestTradeChartImportAnalysis,
                    matchedSetupId: payload.setupId || null,
                    matchedSetupName: payload.setupName || latestTradeChartImportAnalysis.newSetupSuggestedName,
                    matchedSetupConfidence: 'user-confirmed',
                    newSetupSuggestedName: null,
                    newSetupSuggestedDescription: null
                };

                renderTradeChartImportSummary(latestTradeChartImportAnalysis);
                mergeTradeChartImportNote(latestTradeChartImportAnalysis);
                setTradeChartImportStatus('Setup created from the AI suggestion and selected in Core Setup.', 'success');
            } catch (error) {
                setTradeChartImportStatus(error.message || 'Could not create setup from suggestion.', 'danger');
            } finally {
                createSetupButton.disabled = false;
            }
            return;
        }

        const button = event.target.closest('.js-delete-trade-image');
        if (!button) {
            return;
        }

        const tradeId = button.getAttribute('data-trade-id');
        const imageId = button.getAttribute('data-image-id');
        if (!tradeId || !imageId) {
            return;
        }

        if (!confirm('Delete this image?')) {
            return;
        }

        button.disabled = true;
        try {
            await deleteTradeImage(tradeId, imageId);
            const card = document.getElementById(`tradeImageCard_${imageId}`);
            if (card) {
                card.remove();
            }

            const existingImages = document.getElementById('existingTradeImages');
            const emptyState = document.getElementById('existingTradeImagesEmpty');
            const remaining = existingImages
                ? existingImages.querySelectorAll('[id^="tradeImageCard_"]').length
                : 0;

            if (emptyState && remaining === 0) {
                emptyState.classList.remove('d-none');
            }

            showSetupImageInfo('Image deleted.');
            showSetupImageError('');
        } catch (error) {
            showSetupImageError(error.message || 'Image delete failed');
        } finally {
            button.disabled = false;
        }
    });

    if (form && setupImagesInput && !isEditTradePage(form)) {
        setupImagesInput.disabled = true;
        const imageTypeSelect = document.getElementById('setupImageType');
        if (imageTypeSelect) {
            imageTypeSelect.disabled = true;
        }
        showSetupImageInfo('Save trade first. You can upload setup images on the Edit Trade page.');
    }

    updateVolumeHint();
    calculateLiveMetrics();
    validateSetupImages();
    refreshTradeChartImportQueueUi();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTradeFormPage);
} else {
    initTradeFormPage();
}
