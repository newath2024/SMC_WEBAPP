(function () {
    const data = window.adminAnalyticsData || {};
    const chartText = "#64748b";
    const chartGrid = "rgba(148, 163, 184, 0.16)";
    let activeUsersTrendChart = null;
    let sessionPerformanceChart = null;

    function sliceSeries(range) {
        const labels = data.engagementLabels || [];
        const dauValues = data.dauValues || [];
        const wauValues = data.wauValues || [];
        const mauValues = data.mauValues || [];

        if (range === "ALL") {
            return { labels, dauValues, wauValues, mauValues };
        }

        const size = Number(range);
        if (!size || labels.length <= size) {
            return { labels, dauValues, wauValues, mauValues };
        }

        return {
            labels: labels.slice(-size),
            dauValues: dauValues.slice(-size),
            wauValues: wauValues.slice(-size),
            mauValues: mauValues.slice(-size)
        };
    }

    function baseBarOptions(indexAxis) {
        return {
            responsive: true,
            maintainAspectRatio: false,
            indexAxis: indexAxis || "x",
            plugins: {
                legend: { display: false }
            },
            scales: {
                x: {
                    grid: {
                        color: indexAxis === "y" ? chartGrid : "transparent"
                    },
                    ticks: {
                        color: chartText
                    }
                },
                y: {
                    beginAtZero: true,
                    grid: {
                        color: indexAxis === "y" ? "transparent" : chartGrid
                    },
                    ticks: {
                        color: chartText,
                        precision: 0
                    }
                }
            }
        };
    }

    function createBarChart(canvasId, labels, values, color, indexAxis) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) {
            return null;
        }

        return new Chart(canvas, {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: color,
                    borderRadius: 12,
                    borderSkipped: false,
                    maxBarThickness: indexAxis === "y" ? 26 : 44
                }]
            },
            options: baseBarOptions(indexAxis)
        });
    }

    function renderActiveUsersTrend(range) {
        const canvas = document.getElementById("activeUsersTrendChart");
        if (!canvas) {
            return;
        }

        const series = sliceSeries(range);
        if (activeUsersTrendChart) {
            activeUsersTrendChart.destroy();
        }

        activeUsersTrendChart = new Chart(canvas, {
            type: "line",
            data: {
                labels: series.labels,
                datasets: [
                    {
                        label: "DAU",
                        data: series.dauValues,
                        borderColor: "#2563eb",
                        backgroundColor: "rgba(37, 99, 235, 0.12)",
                        borderWidth: 3,
                        tension: 0.35,
                        fill: true,
                        pointRadius: 0,
                        pointHoverRadius: 4
                    },
                    {
                        label: "WAU",
                        data: series.wauValues,
                        borderColor: "#0f766e",
                        borderWidth: 3,
                        tension: 0.35,
                        fill: false,
                        pointRadius: 0,
                        pointHoverRadius: 4
                    },
                    {
                        label: "MAU",
                        data: series.mauValues,
                        borderColor: "#f59e0b",
                        borderWidth: 3,
                        tension: 0.35,
                        fill: false,
                        pointRadius: 0,
                        pointHoverRadius: 4
                    }
                ]
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
                        labels: {
                            color: chartText,
                            usePointStyle: true,
                            pointStyle: "circle"
                        }
                    }
                },
                scales: {
                    x: {
                        grid: { display: false },
                        ticks: { color: chartText }
                    },
                    y: {
                        beginAtZero: true,
                        grid: { color: chartGrid },
                        ticks: {
                            color: chartText,
                            precision: 0
                        }
                    }
                }
            }
        });
    }

    function renderSessionPerformance(view) {
        const canvas = document.getElementById("sessionPerformanceChart");
        if (!canvas) {
            return;
        }

        if (sessionPerformanceChart) {
            sessionPerformanceChart.destroy();
        }

        const values = view === "totalPnl" ? (data.sessionTotalPnlValues || []) : (data.sessionAvgRValues || []);
        const color = view === "totalPnl" ? "#0f766e" : "#2563eb";

        sessionPerformanceChart = new Chart(canvas, {
            type: "bar",
            data: {
                labels: data.sessionLabels || [],
                datasets: [{
                    data: values,
                    backgroundColor: color,
                    borderRadius: 12,
                    borderSkipped: false,
                    maxBarThickness: 44
                }]
            },
            options: {
                ...baseBarOptions("x"),
                scales: {
                    x: {
                        grid: { display: false },
                        ticks: { color: chartText }
                    },
                    y: {
                        grid: { color: chartGrid },
                        ticks: { color: chartText }
                    }
                }
            }
        });
    }

    function initCohortHeatmap() {
        document.querySelectorAll(".cohort-value-cell").forEach(function (cell) {
            const value = Number(cell.getAttribute("data-pct") || "0");
            const alpha = Math.max(0.12, Math.min(0.92, value / 100));
            cell.style.background = "rgba(37, 99, 235, " + alpha + ")";
            cell.style.color = value >= 55 ? "#ffffff" : "#0f172a";
            cell.style.borderColor = "rgba(37, 99, 235, " + Math.max(0.16, alpha) + ")";
        });
    }

    function initRangeToolbar() {
        const buttons = document.querySelectorAll("[data-range-toolbar] .toolbar-chip");
        buttons.forEach(function (button) {
            button.addEventListener("click", function () {
                buttons.forEach(function (item) {
                    item.classList.toggle("is-active", item === button);
                });
                renderActiveUsersTrend(button.getAttribute("data-range") || "90");
            });
        });
    }

    function initSessionToolbar() {
        const buttons = document.querySelectorAll("[data-session-toolbar] .toolbar-chip");
        buttons.forEach(function (button) {
            button.addEventListener("click", function () {
                buttons.forEach(function (item) {
                    item.classList.toggle("is-active", item === button);
                });
                renderSessionPerformance(button.getAttribute("data-session-view") || "avgR");
            });
        });
    }

    function initCharts() {
        renderActiveUsersTrend("90");
        renderSessionPerformance("avgR");
        createBarChart("tradeDistributionChart", data.tradeDistributionLabels || [], data.tradeDistributionValues || [], "#2563eb", "x");
        createBarChart("symbolPopularityChart", data.symbolLabels || [], data.symbolValues || [], "#0f766e", "y");
        createBarChart("setupPopularityChart", data.setupLabels || [], data.setupValues || [], "#2563eb", "y");
        createBarChart("mistakeFrequencyChart", data.mistakeLabels || [], data.mistakeValues || [], "#dc2626", "y");
        createBarChart("rDistributionChart", data.rLabels || [], data.rValues || [], "#f59e0b", "x");

        const donutCanvas = document.getElementById("resultDistributionChart");
        if (donutCanvas) {
            new Chart(donutCanvas, {
                type: "doughnut",
                data: {
                    labels: data.resultLabels || [],
                    datasets: [{
                        data: data.resultValues || [],
                        backgroundColor: data.resultColors || [],
                        borderWidth: 0,
                        hoverOffset: 6
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    cutout: "72%",
                    plugins: {
                        legend: { display: false }
                    }
                }
            });
        }
    }

    function init() {
        initCohortHeatmap();
        initRangeToolbar();
        initSessionToolbar();
        initCharts();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
