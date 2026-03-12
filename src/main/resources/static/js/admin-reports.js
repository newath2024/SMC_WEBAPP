(() => {
  const previewData = window.adminReportPreviewData || {};

  function escapeCsv(value) {
    const raw = String(value ?? "");
    if (raw.includes(",") || raw.includes("\"") || raw.includes("\n")) {
      return `"${raw.replace(/"/g, "\"\"")}"`;
    }
    return raw;
  }

  function download(name, content, mime) {
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  function buildExportContent(preview, format) {
    const delimiter = format === "excel" ? "\t" : ",";
    const lines = [];
    lines.push(["Metric", "Value"].join(delimiter));
    (preview.metrics || []).forEach((item) => {
      lines.push([escapeCsv(item.label), escapeCsv(item.value)].join(delimiter));
    });

    if ((preview.tableHeaders || []).length > 0 && (preview.tableRows || []).length > 0) {
      lines.push("");
      lines.push(preview.tableHeaders.map(escapeCsv).join(delimiter));
      preview.tableRows.forEach((row) => {
        lines.push(row.map(escapeCsv).join(delimiter));
      });
    }
    return lines.join("\n");
  }

  function renderPreview(card, preview) {
    const previewBox = card.querySelector(".report-preview");
    const metricsEl = card.querySelector(".preview-metrics");
    const tableWrap = card.querySelector(".preview-table-wrap");
    const thead = card.querySelector(".preview-table thead");
    const tbody = card.querySelector(".preview-table tbody");
    if (!previewBox || !metricsEl || !tableWrap || !thead || !tbody) return;

    metricsEl.innerHTML = "";
    (preview.metrics || []).forEach((item) => {
      const metric = document.createElement("article");
      metric.className = "preview-metric";
      metric.innerHTML = `<span class="metric-label">${item.label}</span><strong class="metric-value">${item.value}</strong>`;
      metricsEl.appendChild(metric);
    });

    if ((preview.tableHeaders || []).length > 0 && (preview.tableRows || []).length > 0) {
      thead.innerHTML = `<tr>${preview.tableHeaders.map((header) => `<th>${header}</th>`).join("")}</tr>`;
      tbody.innerHTML = preview.tableRows
        .map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join("")}</tr>`)
        .join("");
      tableWrap.hidden = false;
    } else {
      thead.innerHTML = "";
      tbody.innerHTML = "";
      tableWrap.hidden = true;
    }

    previewBox.hidden = false;
  }

  document.querySelectorAll(".report-card").forEach((card) => {
    const key = card.dataset.reportKey;
    const preview = previewData[key];
    const generateBtn = card.querySelector(".js-generate-report");
    const exportBtns = card.querySelectorAll(".js-export-report");
    if (!generateBtn) return;

    generateBtn.addEventListener("click", () => {
      if (!preview) return;
      renderPreview(card, preview);
      exportBtns.forEach((button) => {
        button.disabled = false;
      });
    });

    exportBtns.forEach((button) => {
      button.addEventListener("click", () => {
        if (button.disabled || !preview) return;
        const format = button.dataset.format === "excel" ? "excel" : "csv";
        const now = new Date();
        const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, "0")}${String(now.getDate()).padStart(2, "0")}`;
        const extension = format === "excel" ? "xls" : "csv";
        const mime = format === "excel" ? "application/vnd.ms-excel;charset=utf-8" : "text/csv;charset=utf-8";
        const content = buildExportContent(preview, format);
        download(`${key}-${stamp}.${extension}`, content, mime);
      });
    });
  });
})();
