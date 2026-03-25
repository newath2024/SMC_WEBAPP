(function () {
  const workspace = document.querySelector("[data-settings-workspace]");
  if (!workspace) {
    return;
  }

  const navItems = Array.from(workspace.querySelectorAll("[data-section-trigger]"));
  const mount = workspace.querySelector("[data-section-mount]");
  const savebar = workspace.querySelector("[data-savebar]");
  const saveButton = workspace.querySelector("[data-save-active]");
  const saveLabel = workspace.querySelector("[data-save-label]");
  const saveSpinner = workspace.querySelector("[data-save-spinner]");
  const cancelButton = workspace.querySelector("[data-cancel-edit]");
  const savebarTitle = workspace.querySelector("[data-savebar-title]");
  const savebarText = workspace.querySelector("[data-savebar-text]");
  const saveStatus = workspace.querySelector("[data-save-status]");
  const globalFeedback = workspace.querySelector("[data-global-feedback]");

  const sectionKeys = ["profile", "security", "preferences", "notifications"];
  const sectionState = {};

  let activeSectionKey = resolveInitialSection();
  let activeSectionElement = null;
  let activeForm = null;
  let activeMode = "view";
  let dirty = false;
  let saving = false;
  let feedback = null;
  let initialSerializedValue = "";

  renderSection(activeSectionKey);

  navItems.forEach(function (item) {
    item.addEventListener("click", function () {
      const nextSectionKey = item.getAttribute("data-section-trigger");
      if (!nextSectionKey || nextSectionKey === activeSectionKey) {
        return;
      }

      if (dirty && !window.confirm("You have unsaved changes. Switch sections and discard them?")) {
        return;
      }

      renderSection(nextSectionKey);
    });
  });

  window.addEventListener("beforeunload", function (event) {
    if (!dirty || saving) {
      return;
    }
    event.preventDefault();
    event.returnValue = "";
  });

  function resolveInitialSection() {
    const hash = window.location.hash.replace("#", "").trim();
    if (sectionKeys.includes(hash)) {
      return hash;
    }
    const configured = workspace.getAttribute("data-initial-section");
    return sectionKeys.includes(configured) ? configured : "profile";
  }

  function renderSection(sectionKey) {
    const template = document.getElementById("settings-template-" + sectionKey);
    if (!template || !mount) {
      return;
    }

    mount.replaceChildren(template.content.cloneNode(true));
    activeSectionKey = sectionKey;
    activeSectionElement = mount.querySelector("[data-section-key]");
    activeForm = null;
    activeMode = "view";
    dirty = false;
    saving = false;
    initialSerializedValue = "";
    feedback = readTemplateFeedback(activeSectionElement);

    navItems.forEach(function (item) {
      item.classList.toggle("is-active", item.getAttribute("data-section-trigger") === sectionKey);
    });

    if (window.location.hash !== "#" + sectionKey) {
      history.replaceState(null, "", "#" + sectionKey);
    }

    if (sectionState[sectionKey]) {
      applySectionState(activeSectionElement, sectionState[sectionKey]);
    } else {
      sectionState[sectionKey] = captureSectionState(activeSectionElement);
    }

    animateSectionIn(activeSectionElement);
    hydrateSection();
    syncSavebar();
    syncFeedback();
  }

  function hydrateSection() {
    if (!activeSectionElement) {
      return;
    }

    const editToggle = activeSectionElement.querySelector("[data-edit-toggle]");
    if (editToggle) {
      editToggle.addEventListener("click", function () {
        enterEditMode();
      });
    }

    wireAvatarPreview(activeSectionElement);
  }

  function enterEditMode() {
    if (!activeSectionElement) {
      return;
    }

    const viewMode = activeSectionElement.querySelector('[data-mode="view"]');
    const editMode = activeSectionElement.querySelector('[data-mode="edit"]');
    const form = activeSectionElement.querySelector("[data-settings-form]");
    if (!editMode || !form) {
      return;
    }

    swapModes(viewMode, editMode);
    activeForm = form;
    activeMode = "edit";
    dirty = false;
    saving = false;
    initialSerializedValue = serializeForm(form);

    bindDirtyTracking(form);
    syncSavebar();
    syncFeedback();

    const firstInput = form.querySelector("input, select, textarea");
    if (firstInput) {
      firstInput.focus();
    }
  }

  function bindDirtyTracking(form) {
    if (form.dataset.bound === "true") {
      return;
    }
    form.dataset.bound = "true";

    const updateDirtyState = function () {
      dirty = serializeForm(form) !== initialSerializedValue;
      if (dirty && (!feedback || feedback.type === "success")) {
        feedback = null;
      }
      syncSavebar();
      syncFeedback();
    };

    form.querySelectorAll("input, select, textarea").forEach(function (field) {
      field.addEventListener("input", updateDirtyState);
      field.addEventListener("change", updateDirtyState);
    });

    form.addEventListener("submit", function (event) {
      event.preventDefault();
      submitActiveForm();
    });
  }

  function submitActiveForm() {
    if (!activeForm || saving) {
      return;
    }

    saving = true;
    dirty = false;
    feedback = null;
    syncSavebar();
    syncFeedback();

    const formData = new FormData(activeForm);
    fetch(activeForm.action, {
      method: activeForm.method || "POST",
      body: formData,
      headers: {
        Accept: "application/json",
        "X-Requested-With": "XMLHttpRequest"
      }
    })
      .then(function (response) {
        return response.json().catch(function () {
          return {};
        }).then(function (body) {
          return { ok: response.ok, status: response.status, body: body };
        });
      })
      .then(function (result) {
        saving = false;

        if (!result.ok || result.body.status !== "success") {
          const errorMessage = result.body && result.body.message
            ? result.body.message
            : "We could not save this section.";
          feedback = { type: "error", message: errorMessage };
          dirty = serializeForm(activeForm) !== initialSerializedValue;
          syncSavebar();
          syncFeedback();
          return;
        }

        const viewModel = result.body.viewModel || {};
        sectionState[activeSectionKey] = mergeSectionState(sectionState[activeSectionKey], viewModel);
        applySectionState(activeSectionElement, sectionState[activeSectionKey]);
        feedback = { type: "success", message: result.body.message || "Changes saved." };
        exitEditMode();
      })
      .catch(function () {
        saving = false;
        feedback = { type: "error", message: "A network error interrupted the save. Please try again." };
        dirty = serializeForm(activeForm) !== initialSerializedValue;
        syncSavebar();
        syncFeedback();
      });
  }

  function exitEditMode() {
    if (!activeSectionElement) {
      return;
    }

    const viewMode = activeSectionElement.querySelector('[data-mode="view"]');
    const editMode = activeSectionElement.querySelector('[data-mode="edit"]');
    swapModes(editMode, viewMode);

    activeMode = "view";
    activeForm = null;
    dirty = false;
    initialSerializedValue = "";
    syncSavebar();
    syncFeedback();
  }

  function swapModes(fromElement, toElement) {
    if (fromElement) {
      fromElement.classList.add("is-fading-out");
    }
    window.setTimeout(function () {
      if (fromElement) {
        fromElement.classList.add("d-none");
        fromElement.classList.remove("is-fading-out");
      }
      if (toElement) {
        toElement.classList.remove("d-none");
        toElement.classList.add("is-fading-out");
        requestAnimationFrame(function () {
          toElement.classList.remove("is-fading-out");
        });
      }
    }, 120);
  }

  function animateSectionIn(sectionElement) {
    if (!sectionElement) {
      return;
    }
    sectionElement.classList.add("is-transitioning");
    requestAnimationFrame(function () {
      sectionElement.classList.remove("is-transitioning");
    });
  }

  function captureSectionState(sectionElement) {
    const state = {};

    sectionElement.querySelectorAll("[data-view-field]").forEach(function (field) {
      state[field.getAttribute("data-view-field")] = field.textContent.trim();
    });

    const avatarImage = sectionElement.querySelector("[data-view-avatar-image]");
    const avatarFallback = sectionElement.querySelector("[data-view-avatar-fallback]");
    if (avatarImage || avatarFallback) {
      state.avatarDataUrl = avatarImage && !avatarImage.classList.contains("d-none") ? avatarImage.getAttribute("src") : "";
      state.avatarInitial = avatarFallback ? avatarFallback.textContent.trim() : "";
    }

    sectionElement.querySelectorAll("[data-field-input]").forEach(function (field) {
      const key = field.getAttribute("data-field-input");
      if (!key) {
        return;
      }
      if (field.type === "checkbox") {
        state[key] = field.checked;
      } else {
        state[key] = field.value;
      }
    });

    return state;
  }

  function applySectionState(sectionElement, state) {
    if (!sectionElement || !state) {
      return;
    }

    sectionElement.querySelectorAll("[data-view-field]").forEach(function (field) {
      const key = field.getAttribute("data-view-field");
      if (Object.prototype.hasOwnProperty.call(state, key)) {
        field.textContent = state[key];
      }
    });

    sectionElement.querySelectorAll("[data-field-input]").forEach(function (field) {
      const key = field.getAttribute("data-field-input");
      if (!Object.prototype.hasOwnProperty.call(state, key)) {
        return;
      }
      if (field.type === "checkbox") {
        field.checked = !!state[key];
      } else {
        field.value = state[key];
      }
    });

    const avatarImage = sectionElement.querySelector("[data-view-avatar-image]");
    const avatarFallback = sectionElement.querySelector("[data-view-avatar-fallback]");
    if (avatarImage && avatarFallback && Object.prototype.hasOwnProperty.call(state, "avatarDataUrl")) {
      const hasAvatar = !!state.avatarDataUrl;
      avatarImage.src = hasAvatar ? state.avatarDataUrl : "";
      avatarImage.classList.toggle("d-none", !hasAvatar);
      avatarFallback.classList.toggle("d-none", hasAvatar);
      if (!hasAvatar && state.avatarInitial) {
        avatarFallback.textContent = state.avatarInitial;
      }
    }

    const editAvatarPreview = sectionElement.querySelector("[data-avatar-preview]");
    const editAvatarFallback = sectionElement.querySelector("[data-avatar-fallback]");
    if (editAvatarPreview && editAvatarFallback && Object.prototype.hasOwnProperty.call(state, "avatarDataUrl")) {
      const hasAvatar = !!state.avatarDataUrl;
      editAvatarPreview.src = hasAvatar ? state.avatarDataUrl : "";
      editAvatarPreview.classList.toggle("d-none", !hasAvatar);
      editAvatarFallback.classList.toggle("d-none", hasAvatar);
      if (!hasAvatar && state.avatarInitial) {
        editAvatarFallback.textContent = state.avatarInitial;
      }
    }
  }

  function mergeSectionState(previousState, viewModel) {
    const merged = Object.assign({}, previousState || {});
    Object.keys(viewModel || {}).forEach(function (key) {
      merged[key] = viewModel[key];
    });
    return merged;
  }

  function serializeForm(form) {
    const formData = new FormData(form);
    const pairs = [];
    formData.forEach(function (value, key) {
      if (value instanceof File) {
        pairs.push(key + ":" + (value.name || ""));
      } else {
        pairs.push(key + ":" + value);
      }
    });
    return pairs.sort().join("|");
  }

  function syncSavebar() {
    const canEdit = activeMode === "edit" && !!activeForm;
    if (!savebar || !saveButton || !cancelButton) {
      return;
    }

    savebar.className = "settings-savebar";
    savebar.classList.toggle("d-none", !canEdit && !feedback);
    cancelButton.classList.toggle("d-none", !canEdit);

    if (!canEdit && !feedback) {
      return;
    }

    const label = activeForm
      ? (activeForm.getAttribute("data-dirty-label") || "Section")
      : (activeSectionElement ? (activeSectionElement.getAttribute("data-section-title") || "Section") : "Section");
    const loadingLabel = activeForm ? (activeForm.getAttribute("data-loading-label") || "Saving changes") : "Saving changes";

    if (saving) {
      savebar.classList.add("is-saving");
      saveButton.disabled = true;
      if (saveSpinner) {
        saveSpinner.classList.remove("d-none");
      }
      if (saveLabel) {
        saveLabel.textContent = loadingLabel;
      }
      if (savebarTitle) {
        savebarTitle.textContent = "Saving changes";
      }
      if (savebarText) {
        savebarText.textContent = "Please wait while the server confirms your latest changes.";
      }
      if (saveStatus) {
        saveStatus.textContent = "Saving";
      }
    } else if (feedback && feedback.type === "error") {
      savebar.classList.add("is-error");
      saveButton.disabled = !dirty;
      if (saveSpinner) {
        saveSpinner.classList.add("d-none");
      }
      if (saveLabel) {
        saveLabel.textContent = "Save changes";
      }
      if (savebarTitle) {
        savebarTitle.textContent = label + " could not be saved";
      }
      if (savebarText) {
        savebarText.textContent = feedback.message;
      }
      if (saveStatus) {
        saveStatus.textContent = "Error";
      }
    } else if (canEdit && dirty) {
      savebar.classList.add("is-unsaved");
      saveButton.disabled = false;
      if (saveSpinner) {
        saveSpinner.classList.add("d-none");
      }
      if (saveLabel) {
        saveLabel.textContent = "Save changes";
      }
      if (savebarTitle) {
        savebarTitle.textContent = label + " has unsaved changes";
      }
      if (savebarText) {
        savebarText.textContent = "Review the section and save when you are ready.";
      }
      if (saveStatus) {
        saveStatus.textContent = "Unsaved";
      }
    } else if (feedback && feedback.type === "success") {
      savebar.classList.add("is-saved");
      saveButton.disabled = true;
      if (saveSpinner) {
        saveSpinner.classList.add("d-none");
      }
      if (saveLabel) {
        saveLabel.textContent = "Save changes";
      }
      if (savebarTitle) {
        savebarTitle.textContent = label + " is up to date";
      }
      if (savebarText) {
        savebarText.textContent = feedback.message;
      }
      if (saveStatus) {
        saveStatus.textContent = "Saved";
      }
    } else {
      savebar.classList.add("is-unsaved");
      saveButton.disabled = true;
      if (saveSpinner) {
        saveSpinner.classList.add("d-none");
      }
      if (saveLabel) {
        saveLabel.textContent = "Save changes";
      }
      if (savebarTitle) {
        savebarTitle.textContent = label + " is in edit mode";
      }
      if (savebarText) {
        savebarText.textContent = "Make changes in the active form. Nothing is saved until you submit.";
      }
      if (saveStatus) {
        saveStatus.textContent = "Idle";
      }
    }

    cancelButton.onclick = function () {
      if (!activeSectionKey) {
        return;
      }
      renderSection(activeSectionKey);
    };

    saveButton.onclick = function () {
      if (activeForm) {
        activeForm.requestSubmit();
      }
    };
  }

  function syncFeedback() {
    if (!globalFeedback) {
      return;
    }

    globalFeedback.className = "settings-global-feedback d-none";
    if (!feedback || !feedback.message) {
      globalFeedback.textContent = "";
      return;
    }

    globalFeedback.textContent = feedback.message;
    globalFeedback.classList.remove("d-none");
    globalFeedback.classList.add(feedback.type === "error" ? "is-error" : "is-success");
  }

  function readTemplateFeedback(sectionElement) {
    if (!sectionElement) {
      return null;
    }
    const message = sectionElement.querySelector("[data-feedback]");
    if (!message) {
      return null;
    }
    return {
      type: message.getAttribute("data-feedback") === "error" ? "error" : "success",
      message: message.textContent.trim()
    };
  }

  function wireAvatarPreview(sectionElement) {
    const fileInput = sectionElement.querySelector("[data-avatar-input]");
    const previewImage = sectionElement.querySelector("[data-avatar-preview]");
    const fallback = sectionElement.querySelector("[data-avatar-fallback]");
    const resetButton = sectionElement.querySelector("[data-avatar-reset]");
    const removeAvatarInput = sectionElement.querySelector("[data-remove-avatar]");

    if (!fileInput || !previewImage || !fallback) {
      return;
    }

    function resetPreview(markForRemoval) {
      previewImage.src = "";
      previewImage.classList.add("d-none");
      fallback.classList.remove("d-none");
      fileInput.value = "";
      if (removeAvatarInput) {
        removeAvatarInput.value = markForRemoval ? "true" : "false";
      }
      if (activeForm) {
        dirty = serializeForm(activeForm) !== initialSerializedValue;
        syncSavebar();
      }
    }

    fileInput.addEventListener("change", function () {
      const file = fileInput.files && fileInput.files[0];
      if (!file) {
        resetPreview(false);
        return;
      }

      const reader = new FileReader();
      reader.onload = function (event) {
        const result = event.target && typeof event.target.result === "string" ? event.target.result : "";
        if (!result) {
          resetPreview(false);
          return;
        }

        previewImage.src = result;
        previewImage.classList.remove("d-none");
        fallback.classList.add("d-none");
        if (removeAvatarInput) {
          removeAvatarInput.value = "false";
        }
        if (activeForm) {
          dirty = serializeForm(activeForm) !== initialSerializedValue;
          syncSavebar();
        }
      };
      reader.readAsDataURL(file);
    });

    if (resetButton) {
      resetButton.addEventListener("click", function () {
        resetPreview(true);
      });
    }
  }
})();
