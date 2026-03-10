(function () {
    const data = window.analyticsDashboardData || {};
    const customRangeToggle = document.getElementById("customRangeToggle");
    const customRangeForm = document.getElementById("customRangeForm");
    const dateRangeInput = document.getElementById("dateRange");
    const fromDateInput = document.getElementById("fromDate");
    const toDateInput = document.getElementById("toDate");

    let equityChart = null;
    let setupChart = null;
    let resultChart = null;
    let mistakeChart = null;

    function toggleCustomRange() {
        if (!customRangeForm) {
            return;
        }
        customRangeForm.classList.toggle("is-visible");
    }

    function initDateRangePicker() {
        if (!window.flatpickr || !dateRangeInput || !fromDateInput || !toDateInput) {
            return;
        }

        const defaultDate = [];
        if (data.currentFrom) {
            defaultDate.push(data.currentFrom);
        }
        if (data.currentTo) {
            defaultDate.push(data.currentTo);
        }

        flatpickr(dateRangeInput, {
            mode: "range",
            dateFormat: "Y-m-d",
            defaultDate: defaultDate,
            onChange: function (selectedDates) {
                if (!selectedDates || selectedDates.length === 0) {
                    fromDateInput.value = "";
                    toDateInput.value = "";
                    return;
                }

                const formatLocalDate = function (date) {
                    const year = date.getFullYear();
                    const month = String(date.getMonth() + 1).padStart(2, "0");
                    const day = String(date.getDate()).padStart(2, "0");
                    return year + "-" + month + "-" + day;
                };

                fromDateInput.value = formatLocalDate(selectedDates[0]);
                toDateInput.value = formatLocalDate(selectedDates[selectedDates.length - 1]);
            }
        });
    }

    function setEmptyState(elementId, visible) {
        const element = document.getElementById(elementId);
        if (!element) {
            return;
        }
        element.classList.toggle("is-visible", visible);
    }

    function createGradient(ctx, colorStops) {
        const gradient = ctx.createLinearGradient(0, 0, 0, 320);
        colorStops.forEach(function (stop) {
            gradient.addColorStop(stop.offset, stop.color);
        });
        return gradient;
    }

    function destroyChart(chartRef) {
        if (chartRef) {
            chartRef.destroy();
        }
    }

    function renderEquityChart(view) {
        const canvas = document.getElementById("equityCurveChart");
        if (!canvas) {
            return;
        }

        const labels = data.equityLabels || [];
        const values = view === "r" ? (data.equityRValues || []) : (data.equityPnlValues || []);
        const drawdownValues = data.equityDrawdownValues || [];
        setEmptyState("equityCurveEmpty", labels.length < 2);

        destroyChart(equityChart);
        if (labels.length < 2) {
            equityChart = null;
            return;
        }

        const ctx = canvas.getContext("2d");
        const lineColor = view === "r" ? "#3b82f6" : (values[values.length - 1] >= 0 ? "#22c55e" : "#ef4444");
        const fill = createGradient(ctx, [
            { offset: 0, color: view === "r" ? "rgba(59,130,246,0.28)" : "rgba(34,197,94,0.22)" },
            { offset: 1, color: "rgba(255,255,255,0)" }
        ]);

        const datasets = [{
            label: view === "r" ? "Cumulative R" : "Cumulative PnL",
            data: values,
            borderColor: lineColor,
            backgroundColor: fill,
            fill: true,
            borderWidth: 3,
            tension: 0.34,
            pointRadius: 0,
            pointHoverRadius: 4
        }];

        if (view === "drawdown") {
            datasets.push({
                label: "Drawdown",
                data: drawdownValues,
                borderColor: "#f59e0b",
                borderWidth: 2,
                borderDash: [6, 6],
                fill: false,
                tension: 0.3,
                pointRadius: 0,
                pointHoverRadius: 4
            });
        }

        equityChart = new Chart(canvas, {
            type: "line",
            data: {
                labels: labels,
                datasets: datasets
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: {
                    intersect: false,
                    mode: "index"
                },
                plugins: {
                    legend: {
                        display: view === "drawdown"
                    }
                },
                scales: {
                    x: {
                        grid: {
                            display: false
                        }
                    },
                    y: {
                        grid: {
                            color: "rgba(148,163,184,0.18)"
                        }
                    }
                }
            }
        });
    }

    function renderSetupChart(metric) {
        const canvas = document.getElementById("setupPerformanceChart");
        if (!canvas) {
            return;
        }

        const labels = data.setupLabels || [];
        const metricMap = {
            pnl: {
                values: data.setupPnlValues || [],
                label: "PnL",
                color: "#3b82f6"
            },
            winRate: {
                values: data.setupWinRateValues || [],
                label: "Win rate %",
                color: "#22c55e"
            },
            avgR: {
                values: data.setupAvgRValues || [],
                label: "Avg R",
                color: "#f59e0b"
            }
        };
        const selected = metricMap[metric] || metricMap.pnl;
        setEmptyState("setupPerformanceEmpty", labels.length === 0);

        destroyChart(setupChart);
        if (labels.length === 0) {
            setupChart = null;
            return;
        }

        setupChart = new Chart(canvas, {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    label: selected.label,
                    data: selected.values,
                    backgroundColor: selected.color,
                    borderRadius: 10,
                    borderSkipped: false
                }]
            },
            options: {
                indexAxis: "y",
                responsive: true,
                maintainAspectRatio: false,
                onClick: function (event, elements, chart) {
                    if (!elements.length) {
                        return;
                    }
                    const index = elements[0].index;
                    const setupName = chart.data.labels[index];
                    if (!setupName || !data.tradesBaseUrl) {
                        return;
                    }

                    const url = new URL(data.tradesBaseUrl, window.location.origin);
                    url.searchParams.set("setup", setupName);
                    if (data.currentFrom) {
                        url.searchParams.set("from", data.currentFrom);
                    }
                    if (data.currentTo) {
                        url.searchParams.set("to", data.currentTo);
                    }
                    window.location.href = url.toString();
                },
                plugins: {
                    legend: {
                        display: false
                    }
                },
                scales: {
                    x: {
                        grid: {
                            color: "rgba(148,163,184,0.18)"
                        }
                    },
                    y: {
                        grid: {
                            display: false
                        }
                    }
                }
            }
        });
    }

    function renderResultChart() {
        const canvas = document.getElementById("resultBreakdownChart");
        if (!canvas) {
            return;
        }

        destroyChart(resultChart);
        resultChart = new Chart(canvas, {
            type: "doughnut",
            data: {
                labels: ["Win", "Loss", "BE"],
                datasets: [{
                    data: data.resultValues || [0, 0, 0],
                    backgroundColor: ["#22c55e", "#ef4444", "#f59e0b"],
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: "70%",
                plugins: {
                    legend: {
                        display: false
                    }
                }
            }
        });
    }

    function renderMistakeChart() {
        const canvas = document.getElementById("mistakeFrequencyChart");
        if (!canvas) {
            return;
        }

        const labels = data.mistakeLabels || [];
        const values = data.mistakeCounts || [];
        setEmptyState("mistakeFrequencyEmpty", labels.length === 0);

        destroyChart(mistakeChart);
        if (labels.length === 0) {
            mistakeChart = null;
            return;
        }

        mistakeChart = new Chart(canvas, {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: "#94a3b8",
                    borderRadius: 10,
                    borderSkipped: false
                }]
            },
            options: {
                indexAxis: "y",
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    }
                },
                scales: {
                    x: {
                        beginAtZero: true,
                        ticks: {
                            precision: 0
                        },
                        grid: {
                            color: "rgba(148,163,184,0.18)"
                        }
                    },
                    y: {
                        grid: {
                            display: false
                        }
                    }
                }
            }
        });
    }

    function initEquityToggles() {
        const buttons = document.querySelectorAll("[data-equity-view]");
        buttons.forEach(function (button) {
            button.addEventListener("click", function () {
                buttons.forEach(function (item) {
                    item.classList.remove("is-active");
                });
                button.classList.add("is-active");

                const requestedView = button.getAttribute("data-equity-view");
                renderEquityChart(requestedView === "drawdown" ? "drawdown" : requestedView);
            });
        });
    }

    function initSetupMetricToggles() {
        const buttons = document.querySelectorAll("[data-setup-metric]");
        buttons.forEach(function (button) {
            button.addEventListener("click", function () {
                buttons.forEach(function (item) {
                    item.classList.remove("is-active");
                });
                button.classList.add("is-active");
                renderSetupChart(button.getAttribute("data-setup-metric"));
            });
        });
    }

    function initTableTabs() {
        const tabs = document.querySelectorAll("[data-table-tab]");
        const panels = document.querySelectorAll("[data-table-panel]");
        tabs.forEach(function (tab) {
            tab.addEventListener("click", function () {
                const target = tab.getAttribute("data-table-tab");
                tabs.forEach(function (item) {
                    item.classList.toggle("is-active", item === tab);
                });
                panels.forEach(function (panel) {
                    panel.classList.toggle("is-active", panel.getAttribute("data-table-panel") === target);
                });
            });
        });
    }

    function init() {
        initDateRangePicker();
        initEquityToggles();
        initSetupMetricToggles();
        initTableTabs();
        renderEquityChart("pnl");
        renderSetupChart("pnl");
        renderResultChart();
        renderMistakeChart();

        if (customRangeToggle) {
            customRangeToggle.addEventListener("click", toggleCustomRange);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
