const MAX_TRADE_DETAIL_IMAGE_SIZE_BYTES = 1024 * 1024; // 1MB per file

function formatTradeDetailFileSize(bytes) {
  if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(2) + 'MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(2) + 'KB';
  return bytes + 'B';
}

function setTradeDetailImageFeedback(group, message, tone) {
  const feedback = group.querySelector('.js-trade-image-feedback');
  if (!feedback) {
    return;
  }

  feedback.textContent = message || '';
  feedback.classList.remove('is-success', 'is-error');
  if (tone === 'success' || tone === 'error') {
    feedback.classList.add(`is-${tone}`);
  }
}

function setTradeDetailImageUploading(group, isUploading) {
  const zone = group.querySelector('.js-trade-image-dropzone');
  const input = group.querySelector('.js-trade-image-upload-input');
  const triggers = group.querySelectorAll('.js-trade-image-upload-trigger');

  if (zone) {
    zone.classList.toggle('is-uploading', isUploading);
  }

  if (input) {
    input.disabled = isUploading;
  }

  triggers.forEach(function (trigger) {
    trigger.disabled = isUploading;
  });
}

function validateTradeDetailImages(files) {
  if (!files || files.length === 0) {
    return 'Choose at least one image to upload.';
  }

  const oversized = files.filter(function (file) {
    return file.size > MAX_TRADE_DETAIL_IMAGE_SIZE_BYTES;
  });

  if (oversized.length === 0) {
    return null;
  }

  const fileNames = oversized
    .map(function (file) {
      return `${file.name} (${formatTradeDetailFileSize(file.size)})`;
    })
    .join(', ');

  return `File too large. Max 1MB each. Oversized: ${fileNames}`;
}

async function uploadTradeDetailImages(tradeId, imageType, files) {
  const formData = new FormData();
  files.forEach(function (file) {
    formData.append('files', file);
  });
  formData.append('imageType', imageType || 'SETUP');

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

  if (response.status === 401) {
    window.location.href = '/login';
    throw new Error('Session expired. Redirecting to login...');
  }

  if (!response.ok) {
    throw new Error(payload && payload.message ? payload.message : 'Image upload failed');
  }

  return payload;
}

async function processTradeDetailImageUpload(group, tradeId, files) {
  const imageType = group.getAttribute('data-image-type') || 'SETUP';
  const imageLabel = group.getAttribute('data-image-label') || 'Image';
  const input = group.querySelector('.js-trade-image-upload-input');
  const validationError = validateTradeDetailImages(files);

  if (validationError) {
    setTradeDetailImageFeedback(group, validationError, 'error');
    if (input) {
      input.value = '';
    }
    return;
  }

  try {
    setTradeDetailImageUploading(group, true);
    setTradeDetailImageFeedback(group, `Uploading ${files.length} image${files.length === 1 ? '' : 's'}...`);
    await uploadTradeDetailImages(tradeId, imageType, files);
    setTradeDetailImageFeedback(group, `${imageLabel} image${files.length === 1 ? '' : 's'} uploaded. Refreshing...`, 'success');
    window.setTimeout(function () {
      window.location.reload();
    }, 350);
  } catch (error) {
    setTradeDetailImageFeedback(group, error.message || 'Image upload failed', 'error');
  } finally {
    setTradeDetailImageUploading(group, false);
    if (input) {
      input.value = '';
    }
  }
}

function initTradeDetailImageUpload() {
  const manager = document.querySelector('.js-trade-image-manager');
  if (!manager) {
    return;
  }

  const tradeId = manager.getAttribute('data-trade-id');
  if (!tradeId) {
    return;
  }

  const groups = manager.querySelectorAll('.js-trade-image-group');
  groups.forEach(function (group) {
    const input = group.querySelector('.js-trade-image-upload-input');
    const zone = group.querySelector('.js-trade-image-dropzone');
    const triggers = group.querySelectorAll('.js-trade-image-upload-trigger');
    let dragDepth = 0;

    if (!input || !zone || triggers.length === 0) {
      return;
    }

    triggers.forEach(function (trigger) {
      trigger.addEventListener('click', function () {
        input.click();
      });
    });

    input.addEventListener('change', function () {
      const files = Array.from(input.files || []);
      processTradeDetailImageUpload(group, tradeId, files);
    });

    zone.addEventListener('dragenter', function (event) {
      event.preventDefault();
      dragDepth += 1;
      zone.classList.add('is-dragover');
    });

    zone.addEventListener('dragover', function (event) {
      event.preventDefault();
      if (event.dataTransfer) {
        event.dataTransfer.dropEffect = 'copy';
      }
      zone.classList.add('is-dragover');
    });

    zone.addEventListener('dragleave', function (event) {
      event.preventDefault();
      dragDepth = Math.max(0, dragDepth - 1);
      if (dragDepth === 0) {
        zone.classList.remove('is-dragover');
      }
    });

    zone.addEventListener('drop', function (event) {
      event.preventDefault();
      dragDepth = 0;
      zone.classList.remove('is-dragover');
      const files = Array.from((event.dataTransfer && event.dataTransfer.files) || []);
      processTradeDetailImageUpload(group, tradeId, files);
    });
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initTradeDetailImageUpload);
} else {
  initTradeDetailImageUpload();
}
