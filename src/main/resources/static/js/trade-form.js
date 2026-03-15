const MAX_SETUP_IMAGE_SIZE_BYTES = 1024 * 1024; // 1MB per file
const MAX_TRADE_CHART_IMPORT_SIZE_BYTES = 10 * 1024 * 1024; // 10MB per file
let isSubmittingTradeForm = false;
let isAnalyzingTradeChart = false;
let initialStopLossManuallyEdited = false;

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

function setTradeChartImportBusy(isBusy) {
    const button = getTradeChartImportButton();
    const input = getTradeChartImportInput();

    isAnalyzingTradeChart = isBusy;
    if (input) {
        input.disabled = isBusy || input.hasAttribute('data-permanently-disabled');
    }
    if (button) {
        if (!button.dataset.originalHtml) {
            button.dataset.originalHtml = button.innerHTML || 'Analyze Screenshot';
        }
        button.disabled = isBusy || !!input?.disabled;
        button.innerHTML = isBusy ? 'Analyzing Screenshot...' : button.dataset.originalHtml;
    }
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

function renderTradeChartImportSummary(analysis) {
    const mappings = [
        ['tradeChartImportSymbol', formatImportedValue(analysis.symbol)],
        ['tradeChartImportDirection', formatImportedValue(analysis.direction)],
        ['tradeChartImportEntry', formatImportedPrice(analysis.entryPrice)],
        ['tradeChartImportStopLoss', formatImportedPrice(analysis.stopLoss)],
        ['tradeChartImportTakeProfit', formatImportedPrice(analysis.takeProfit)],
        ['tradeChartImportTimeframe', formatImportedValue(analysis.timeframe)],
        ['tradeChartImportSetupGuess', formatImportedValue(analysis.setupGuess)]
    ];

    mappings.forEach(([id, value]) => {
        const el = document.getElementById(id);
        if (el) {
            el.textContent = value;
        }
    });

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

function buildTradeChartImportNote(analysis) {
    const fragments = ['Imported from TradingView screenshot'];
    if (analysis.timeframe) {
        fragments.push(`Timeframe: ${analysis.timeframe}`);
    }
    if (analysis.setupGuess) {
        fragments.push(`Setup guess: ${analysis.setupGuess}`);
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
        .filter((line) => line && !line.startsWith('Imported from TradingView screenshot'));

    noteInput.value = [importNote, ...existingLines].join('\n');
}

function applyTradeChartAnalysis(analysis) {
    if (!analysis) return;

    const symbolSelect = document.getElementById('symbol');
    const directionSelect = document.getElementById('direction');
    const ltfSelect = document.getElementById('ltf');
    const htfSelect = document.getElementById('htf');
    const entryInput = document.getElementById('entryPrice');
    const stopLossInput = document.getElementById('stopLoss');
    const initialStopLossInput = document.getElementById('initialStopLoss');
    const takeProfitInput = document.querySelector('input[name="takeProfit"]');

    if (analysis.symbol && symbolSelect) {
        ensureSelectOption(symbolSelect, analysis.symbol);
    }

    if (analysis.direction && directionSelect) {
        ensureSelectOption(directionSelect, analysis.direction);
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

    if (analysis.timeframe) {
        if (ltfSelect) {
            ensureSelectOption(ltfSelect, analysis.timeframe);
        }
        if (htfSelect && !htfSelect.value && (analysis.timeframe === 'H4' || analysis.timeframe === 'H1')) {
            ensureSelectOption(htfSelect, analysis.timeframe);
        }
    }

    if (analysis.setupGuess) {
        trySelectSetupByName(analysis.setupGuess);
    }

    mergeTradeChartImportNote(analysis);
    updateVolumeHint();
    calculateLiveMetrics();
}

function validateTradeChartImportFile(file) {
    if (!file) {
        throw new Error('Choose a TradingView screenshot first.');
    }
    if (!file.type || !file.type.startsWith('image/')) {
        throw new Error('Only image files are supported for TradingView screenshot import.');
    }
    if (file.size > MAX_TRADE_CHART_IMPORT_SIZE_BYTES) {
        throw new Error('TradingView screenshot must be 10MB or smaller.');
    }
}

async function analyzeTradeChartImage(file) {
    const formData = new FormData();
    formData.append('file', file);

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

    const file = input.files && input.files[0] ? input.files[0] : null;

    try {
        validateTradeChartImportFile(file);
        setTradeChartImportBusy(true);
        setTradeChartImportStatus('Analyzing TradingView screenshot...', 'info');

        const payload = await analyzeTradeChartImage(file);
        const analysis = payload && payload.analysis ? payload.analysis : null;
        if (!analysis) {
            throw new Error('TradingView screenshot analysis returned no data.');
        }

        renderTradeChartImportSummary(analysis);
        applyTradeChartAnalysis(analysis);
        setTradeChartImportStatus('Screenshot analyzed. The values were added to the form below. Review them, then click Save Trade.', 'success');
    } catch (error) {
        setTradeChartImportReviewActionsVisible(false);
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
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTradeFormPage);
} else {
    initTradeFormPage();
}
