(function () {
  const searchInput = document.getElementById("mistakeSearch");
  const statusFilter = document.getElementById("statusFilter");
  const tableBody = document.getElementById("mistakeTableBody");
  const filteredEmptyState = document.getElementById("filteredEmptyState");
  const statFilterCards = Array.from(document.querySelectorAll(".stat-filter-card"));
  const statInsightCards = Array.from(document.querySelectorAll(".stat-insight-card"));

  function normalize(value) {
    return (value || "").toString().trim().toLowerCase();
  }

  function applyTableFilters() {
    if (!tableBody) return;

    const rows = Array.from(tableBody.querySelectorAll("tr"));
    const keyword = normalize(searchInput ? searchInput.value : "");
    const status = normalize(statusFilter ? statusFilter.value : "all");
    let visibleCount = 0;

    rows.forEach(function (row) {
      const rowSearch = normalize(row.dataset.search);
      const rowStatus = normalize(row.dataset.status);
      const matchesSearch = !keyword || rowSearch.includes(keyword);
      const matchesStatus = status === "all" || rowStatus === status;
      const isVisible = matchesSearch && matchesStatus;

      row.style.display = isVisible ? "" : "none";
      if (isVisible) visibleCount += 1;
    });

    if (filteredEmptyState) {
      filteredEmptyState.classList.toggle("d-none", visibleCount > 0);
    }
  }

  if (searchInput) {
    searchInput.addEventListener("input", applyTableFilters);
  }

  if (statusFilter) {
    statusFilter.addEventListener("change", applyTableFilters);
  }

  statFilterCards.forEach(function (card) {
    const applyCardFilter = function () {
      const targetStatus = (card.dataset.filterStatus || "all").toLowerCase();
      if (statusFilter) {
        statusFilter.value = targetStatus;
      }
      applyTableFilters();
    };

    card.addEventListener("click", applyCardFilter);
    card.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        applyCardFilter();
      }
    });
  });

  statInsightCards.forEach(function (card) {
    const scrollToInsights = function () {
      const targetSelector = card.dataset.scrollTarget;
      if (!targetSelector) return;
      const target = document.querySelector(targetSelector);
      if (target) {
        target.scrollIntoView({ behavior: "smooth", block: "start" });
      }
    };

    card.addEventListener("click", scrollToInsights);
    card.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        scrollToInsights();
      }
    });
  });

  applyTableFilters();

  if (window.bootstrap) {
    Array.from(document.querySelectorAll('[data-bs-toggle="tooltip"]')).forEach(function (el) {
      new window.bootstrap.Tooltip(el);
    });
  }

  const modalElement = document.getElementById("deleteMistakeModal");
  const deleteForm = document.getElementById("deleteMistakeForm");
  const deleteName = document.getElementById("deleteMistakeName");
  const editModalElement = document.getElementById("editMistakeModal");
  const editForm = document.getElementById("editMistakeForm");
  const editCodeInput = document.getElementById("editMistakeCode");
  const editNameInput = document.getElementById("editMistakeNameInput");
  const editDescriptionInput = document.getElementById("editMistakeDescription");
  const editStatusTrue = document.getElementById("editMistakeActiveTrue");
  const editStatusFalse = document.getElementById("editMistakeActiveFalse");

  if (window.bootstrap && modalElement && deleteForm && deleteName) {
    const deleteModal = new window.bootstrap.Modal(modalElement);

    Array.from(document.querySelectorAll(".delete-mistake-btn")).forEach(function (btn) {
      btn.addEventListener("click", function () {
        const action = btn.dataset.deleteAction;
        const name = btn.dataset.deleteName || "this mistake tag";
        if (!action) return;

        deleteForm.action = action;
        deleteName.textContent = name;
        deleteModal.show();
      });
    });
  }

  if (window.bootstrap && editModalElement && editForm) {
    const editModal = new window.bootstrap.Modal(editModalElement);

    Array.from(document.querySelectorAll(".edit-mistake-btn")).forEach(function (btn) {
      btn.addEventListener("click", function () {
        const id = btn.dataset.editId;
        const code = btn.dataset.editCode || "";
        const name = btn.dataset.editName || "";
        const description = btn.dataset.editDescription || "";
        const isActive = (btn.dataset.editActive || "true") === "true";

        if (!id) return;

        editForm.action = "/mistakes/" + id + "/edit";
        if (editCodeInput) editCodeInput.value = code;
        if (editNameInput) editNameInput.value = name;
        if (editDescriptionInput) editDescriptionInput.value = description;
        if (editStatusTrue) editStatusTrue.checked = isActive;
        if (editStatusFalse) editStatusFalse.checked = !isActive;

        editModal.show();
      });
    });
  }
})();
