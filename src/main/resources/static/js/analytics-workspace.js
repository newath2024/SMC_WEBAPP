(function () {
    const data = window.analyticsWorkspaceData || {};
    const charts = [];

    function setEmptyState(elementId, visible) {
        const element = document.getElementById(elementId);
        if (!element) {
            return;
        }
        element.classList.toggle("is-visible", visible);
    }

    function renderChart(canvasId, config, emptyStateId, hasData) {
        const canvas = document.getElementById(canvasId);
        if (!canvas || !window.Chart) {
            return;
        }

        setEmptyState(emptyStateId, !hasData);
        if (!hasData) {
            return;
        }

        const chart = new Chart(canvas, config);
        charts.push(chart);
    }

    function initDateRangePicker() {
        const dateRangeInput = document.getElementById("dateRange");
        const fromDateInput = document.getElementById("fromDate");
        const toDateInput = document.getElementById("toDate");
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

    function commonBarOptions() {
        return {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                }
            },
            scales: {
                x: {
                    grid: {
                        color: "rgba(15, 23, 42, 0.08)"
                    },
                    ticks: {
                        color: "#627064"
                    }
                },
                y: {
                    grid: {
                        display: false
                    },
                    ticks: {
                        color: "#627064"
                    }
                }
            }
        };
    }

    function renderRDistributionChart() {
        const labels = data.rDistributionLabels || [];
        const values = data.rDistributionCounts || [];
        renderChart("rDistributionChart", {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: labels.map(function (_, index) {
                        return index < 3 ? "rgba(194, 65, 12, 0.75)" : "rgba(31, 143, 99, 0.82)";
                    }),
                    borderRadius: 12,
                    borderSkipped: false
                }]
            },
            options: commonBarOptions()
        }, "rDistributionEmpty", values.some(function (value) { return value > 0; }));
    }

    function renderExpectancyChart() {
        const labels = data.expectancyLabels || [];
        const values = data.expectancyValues || [];
        renderChart("expectancyBySetupChart", {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: values.map(function (value) {
                        return value >= 0 ? "#1447e6" : "#c2410c";
                    }),
                    borderRadius: 12,
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
                        grid: {
                            color: "rgba(15, 23, 42, 0.08)"
                        },
                        ticks: {
                            color: "#627064",
                            callback: function (value) {
                                return value + "R";
                            }
                        }
                    },
                    y: {
                        grid: {
                            display: false
                        },
                        ticks: {
                            color: "#627064"
                        }
                    }
                }
            }
        }, "expectancyBySetupEmpty", labels.length > 0);
    }

    function renderProcessScoreDistributionChart() {
        const labels = data.processScoreLabels || [];
        const values = data.processScoreCounts || [];
        renderChart("processScoreDistributionChart", {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: [
                        "rgba(194, 65, 12, 0.82)",
                        "rgba(245, 158, 11, 0.82)",
                        "rgba(20, 71, 230, 0.82)",
                        "rgba(31, 143, 99, 0.82)"
                    ],
                    borderRadius: 12,
                    borderSkipped: false
                }]
            },
            options: commonBarOptions()
        }, "processScoreDistributionEmpty", values.some(function (value) { return value > 0; }));
    }

    function renderHoldingTimeChart() {
        const labels = data.holdingLabels || [];
        const tradeCounts = data.holdingTradeCounts || [];
        const avgRValues = data.holdingAvgRValues || [];
        renderChart("holdingTimeChart", {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    type: "bar",
                    label: "Trades",
                    data: tradeCounts,
                    backgroundColor: "rgba(20, 71, 230, 0.82)",
                    borderRadius: 12,
                    borderSkipped: false,
                    yAxisID: "y"
                }, {
                    type: "line",
                    label: "Avg R",
                    data: avgRValues,
                    borderColor: "#1f8f63",
                    backgroundColor: "#1f8f63",
                    borderWidth: 3,
                    tension: 0.35,
                    pointRadius: 3,
                    yAxisID: "y1"
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        position: "top",
                        labels: {
                            color: "#627064"
                        }
                    }
                },
                scales: {
                    x: {
                        grid: {
                            display: false
                        },
                        ticks: {
                            color: "#627064"
                        }
                    },
                    y: {
                        beginAtZero: true,
                        grid: {
                            color: "rgba(15, 23, 42, 0.08)"
                        },
                        ticks: {
                            color: "#627064",
                            precision: 0
                        }
                    },
                    y1: {
                        position: "right",
                        grid: {
                            drawOnChartArea: false
                        },
                        ticks: {
                            color: "#627064",
                            callback: function (value) {
                                return value + "R";
                            }
                        }
                    }
                }
            }
        }, "holdingTimeEmpty", tradeCounts.some(function (value) { return value > 0; }));
    }

    function renderSessionPerformanceChart() {
        const labels = data.sessionLabels || [];
        const values = data.sessionAvgRValues || [];
        const processScores = data.sessionAvgProcessScores || [];
        const totalPnls = data.sessionTotalPnlValues || [];
        const options = commonBarOptions();
        options.scales.y.ticks.callback = function (value) {
            return value + "R";
        };
        options.plugins.tooltip = {
            callbacks: {
                label: function (context) {
                    return "Avg R: " + context.parsed.y + "R";
                },
                afterLabel: function (context) {
                    const index = context.dataIndex;
                    const lines = [];
                    const processScore = processScores[index];
                    const totalPnl = totalPnls[index];
                    lines.push("Avg process score: " + (processScore == null ? "N/A" : processScore));
                    if (totalPnl != null) {
                        lines.push("Total PnL: " + (totalPnl >= 0 ? "+$" : "-$") + Math.abs(totalPnl).toFixed(2));
                    }
                    return lines;
                }
            }
        };

        renderChart("sessionPerformanceChart", {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: values.map(function (value) {
                        return value >= 0 ? "#1f8f63" : "#c2410c";
                    }),
                    borderRadius: 12,
                    borderSkipped: false
                }]
            },
            options: options
        }, "sessionPerformanceEmpty", labels.length > 0);
    }

    function renderDayOfWeekChart() {
        const labels = data.weekdayLabels || [];
        const values = data.weekdayAvgRValues || [];
        renderChart("dayOfWeekChart", {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: values.map(function (value) {
                        return value >= 0 ? "rgba(31, 143, 99, 0.82)" : "rgba(194, 65, 12, 0.82)";
                    }),
                    borderRadius: 12,
                    borderSkipped: false
                }]
            },
            options: commonBarOptions()
        }, "dayOfWeekEmpty", labels.length > 0);
    }

    function init() {
        initDateRangePicker();
        renderRDistributionChart();
        renderExpectancyChart();
        renderProcessScoreDistributionChart();
        renderHoldingTimeChart();
        renderSessionPerformanceChart();
        renderDayOfWeekChart();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
