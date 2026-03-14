(function () {
  const fileInput = document.querySelector("[data-avatar-input]");
  const previewImage = document.querySelector("[data-avatar-preview]");
  const fallback = document.querySelector("[data-avatar-fallback]");
  const resetButton = document.querySelector("[data-avatar-reset]");
  const removeAvatarInput = document.querySelector("[data-remove-avatar]");

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
    };
    reader.readAsDataURL(file);
  });

  if (resetButton) {
    resetButton.addEventListener("click", function () {
      resetPreview(true);
    });
  }
})();
