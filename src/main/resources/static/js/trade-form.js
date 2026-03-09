const MAX_SETUP_IMAGE_SIZE_BYTES = 1024 * 1024; // 1MB per file
let isSubmittingTradeForm = false;

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
    const symbol = document.getElementById('symbol')?.value;
    const direction = document.getElementById('direction')?.value;
    const result = document.getElementById('result')?.value;

    const entry = parseNum(document.getElementById('entryPrice')?.value);
    const sl = parseNum(document.getElementById('stopLoss')?.value);
    const exit = parseNum(document.getElementById('exitPrice')?.value);
    const size = parseNum(document.getElementById('positionSize')?.value);

    const pnlInput = document.getElementById('pnl');
    const rInput = document.getElementById('rMultiple');

    if (!pnlInput || !rInput) return;

    pnlInput.value = '';
    rInput.value = '';

    const r = calculateRMultiple(direction, result, entry, sl, exit);
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

async function uploadSetupImages(tradeId, files) {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));

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
        await uploadSetupImages(tradeId, files);
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
    if (setupImagesInput) {
        setupImagesInput.addEventListener('change', validateSetupImages);
    }

    const form = getTradeForm();
    if (form) {
        form.addEventListener('submit', handleTradeFormSubmit);
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
