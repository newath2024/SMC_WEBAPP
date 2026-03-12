(function () {
  const data = window.adminBillingData || {};

  const axisBase = {
    maintainAspectRatio: false,
    plugins: {
      legend: {
        labels: {
          color: "#334155"
        }
      }
    },
    scales: {
      x: {
        ticks: { color: "#64748b", maxRotation: 0, autoSkip: true, maxTicksLimit: 10 },
        grid: { display: false }
      },
      y: {
        beginAtZero: true,
        ticks: { color: "#64748b" },
        grid: { color: "rgba(148,163,184,0.2)" }
      }
    }
  };

  const revenueEl = document.getElementById("revenueTrendChart");
  if (revenueEl) {
    new Chart(revenueEl, {
      type: "line",
      data: {
        labels: data.revenueTrendLabels || [],
        datasets: [{
          label: "Revenue (USD)",
          data: data.revenueTrendValues || [],
          borderColor: "#0284c7",
          backgroundColor: "rgba(2,132,199,0.14)",
          borderWidth: 3,
          fill: true,
          tension: 0.35,
          pointRadius: 2,
          pointBackgroundColor: "#0ea5e9"
        }]
      },
      options: axisBase
    });
  }

  const planMixEl = document.getElementById("planMixChart");
  if (planMixEl) {
    const planLabels = data.planMixLabels || [];
    const planValues = data.planMixValues || [];
    new Chart(planMixEl, {
      type: "doughnut",
      data: {
        labels: planLabels,
        datasets: [{
          data: planValues,
          backgroundColor: ["#2563eb", "#f59e0b", "#94a3b8"],
          borderWidth: 0,
          hoverOffset: 5
        }]
      },
      options: {
        maintainAspectRatio: false,
        cutout: "65%",
        plugins: {
          legend: {
            position: "bottom",
            labels: {
              color: "#334155",
              boxWidth: 10,
              generateLabels: function(chart) {
                const defaults = Chart.defaults.plugins.legend.labels.generateLabels(chart);
                return defaults.map((item) => {
                  const value = planValues[item.index] || 0;
                  const label = planLabels[item.index] || "";
                  return { ...item, text: `${label} — ${value} users` };
                });
              }
            }
          }
        }
      }
    });
  }

  const paymentStatusEl = document.getElementById("paymentStatusChart");
  if (paymentStatusEl) {
    new Chart(paymentStatusEl, {
      type: "bar",
      data: {
        labels: data.paymentStatusLabels || [],
        datasets: [{
          label: "Subscriptions",
          data: data.paymentStatusValues || [],
          backgroundColor: ["#16a34a", "#f59e0b", "#dc2626", "#64748b"],
          borderRadius: 10,
          borderSkipped: false,
          maxBarThickness: 38
        }]
      },
      options: {
        ...axisBase,
        plugins: {
          legend: { display: false }
        }
      }
    });
  }
})();
