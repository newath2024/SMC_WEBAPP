(function () {
    const data = window.reportsPageData || {};
    const outcomeCenterTextPlugin = {
        id: "outcomeCenterText",
        afterDraw: function (chart) {
            if (chart.canvas.id !== "reportsOutcomeChart") {
                return;
            }
            const meta = chart.getDatasetMeta(0);
            if (!meta || !meta.data || !meta.data.length) {
                return;
            }

            const total = data.outcomeTotal || 0;
            const x = meta.data[0].x;
            const y = meta.data[0].y;
            const ctx = chart.ctx;

            ctx.save();
            ctx.textAlign = "center";
            ctx.fillStyle = "#0f172a";
            ctx.font = "800 24px Manrope";
            ctx.fillText(String(total), x, y - 4);
            ctx.fillStyle = "#64748b";
            ctx.font = "700 12px Manrope";
            ctx.fillText("trades", x, y + 16);
            ctx.restore();
        }
    };

    Chart.register(outcomeCenterTextPlugin);

    function setEmptyState(elementId, visible) {
        const element = document.getElementById(elementId);
        if (!element) {
            return;
        }
        element.classList.toggle("is-visible", visible);
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

    function renderEquityChart() {
        const canvas = document.getElementById("reportsEquityChart");
        const labels = data.equityLabels || [];
        const values = data.equityValues || [];
        if (!canvas) {
            return;
        }

        setEmptyState("reportsEquityEmpty", labels.length < 2);
        if (labels.length < 2) {
            return;
        }

        const ctx = canvas.getContext("2d");
        const gradient = ctx.createLinearGradient(0, 0, 0, 360);
        gradient.addColorStop(0, "rgba(59, 130, 246, 0.28)");
        gradient.addColorStop(1, "rgba(59, 130, 246, 0)");

        new Chart(canvas, {
            type: "line",
            data: {
                labels: labels,
                datasets: [{
                    label: "Cumulative R",
                    data: values,
                    borderColor: "#2563eb",
                    backgroundColor: gradient,
                    fill: true,
                    borderWidth: 3,
                    tension: 0.32,
                    pointRadius: 0,
                    pointHoverRadius: 4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        callbacks: {
                            title: function (items) {
                                return items.length ? items[0].label : "";
                            },
                            label: function (context) {
                                return `${Number(context.parsed.y || 0).toFixed(2)}R`;
                            }
                        }
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
                            color: "rgba(148, 163, 184, 0.18)"
                        }
                    }
                }
            }
        });
    }

    function renderOutcomeChart() {
        const canvas = document.getElementById("reportsOutcomeChart");
        if (!canvas) {
            return;
        }

        new Chart(canvas, {
            type: "doughnut",
            data: {
                labels: ["Wins", "Losses", "Breakeven"],
                datasets: [{
                    data: data.outcomeValues || [0, 0, 0],
                    backgroundColor: ["#22c55e", "#ef4444", "#f59e0b"],
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: "68%",
                plugins: {
                    legend: {
                        position: "bottom",
                        labels: {
                            usePointStyle: true,
                            padding: 16
                        }
                    }
                }
            }
        });
    }

    function renderRDistributionChart() {
        const canvas = document.getElementById("reportsRDistributionChart");
        const labels = (data.rDistributionLabels || []).map(function (label) {
            const labelMap = {
                "<= -2R": "-2R",
                "-2R to -1R": "-1R",
                "-1R to 0R": "0R",
                "0R to 1R": "1R",
                "1R to 2R": "2R",
                "2R to 3R": "3R",
                ">= 3R": "3R+"
            };
            return labelMap[label] || label;
        });
        const values = data.rDistributionValues || [];
        if (!canvas) {
            return;
        }

        setEmptyState("reportsRDistributionEmpty", labels.length === 0);
        if (labels.length === 0) {
            return;
        }

        new Chart(canvas, {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    label: "Trades",
                    data: values,
                    backgroundColor: "#38bdf8",
                    borderRadius: 12,
                    borderSkipped: false
                }]
            },
            options: {
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
                            display: false
                        }
                    },
                    y: {
                        beginAtZero: true,
                        ticks: {
                            precision: 0
                        },
                        grid: {
                            color: "rgba(148, 163, 184, 0.18)"
                        }
                    }
                }
            }
        });
    }

    function renderMistakeChart() {
        const canvas = document.getElementById("reportsMistakeChart");
        const labels = (data.mistakeLabels || []).map(function (label, index) {
            const value = (data.mistakeValues || [])[index] || 0;
            return `${label} (${value})`;
        });
        const values = data.mistakeValues || [];
        if (!canvas) {
            return;
        }

        setEmptyState("reportsMistakeEmpty", labels.length === 0);
        if (labels.length === 0) {
            return;
        }

        new Chart(canvas, {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: "#94a3b8",
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
                        beginAtZero: true,
                        ticks: {
                            precision: 0
                        },
                        grid: {
                            color: "rgba(148, 163, 184, 0.18)"
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

    function renderSessionChart() {
        const canvas = document.getElementById("reportsSessionChart");
        const labels = data.sessionLabels || [];
        const values = data.sessionValues || [];
        if (!canvas) {
            return;
        }

        setEmptyState("reportsSessionEmpty", labels.length === 0);
        if (labels.length === 0) {
            return;
        }

        new Chart(canvas, {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    backgroundColor: labels.map(function (_, index) {
                        const palette = ["#38bdf8", "#2563eb", "#0f766e", "#64748b"];
                        return palette[index % palette.length];
                    }),
                    borderRadius: 12,
                    borderSkipped: false
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        callbacks: {
                            label: function (context) {
                                return `${Number(context.parsed.y || 0).toFixed(2)}R avg`;
                            }
                        }
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
                            color: "rgba(148, 163, 184, 0.18)"
                        }
                    }
                }
            }
        });
    }

    function renderWeekdayChart() {
        const canvas = document.getElementById("reportsWeekdayChart");
        const labels = data.weekdayLabels || [];
        const values = data.weekdayValues || [];
        if (!canvas) {
            return;
        }

        setEmptyState("reportsWeekdayEmpty", labels.length === 0);
        if (labels.length === 0) {
            return;
        }

        new Chart(canvas, {
            type: "line",
            data: {
                labels: labels,
                datasets: [{
                    data: values,
                    borderColor: "#7c3aed",
                    backgroundColor: "rgba(124, 58, 237, 0.12)",
                    fill: true,
                    borderWidth: 3,
                    tension: 0.3,
                    pointRadius: 4,
                    pointHoverRadius: 5,
                    pointBackgroundColor: "#7c3aed"
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        callbacks: {
                            label: function (context) {
                                return `${Number(context.parsed.y || 0).toFixed(2)}R avg`;
                            }
                        }
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
                            color: "rgba(148, 163, 184, 0.18)"
                        }
                    }
                }
            }
        });
    }

    function initPdfButton() {
        const button = document.getElementById("downloadPdfButton");
        if (!button) {
            return;
        }
        button.addEventListener("click", function () {
            window.print();
        });
    }

    function init() {
        initDateRangePicker();
        renderEquityChart();
        renderOutcomeChart();
        renderRDistributionChart();
        renderMistakeChart();
        renderSessionChart();
        renderWeekdayChart();
        initPdfButton();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
