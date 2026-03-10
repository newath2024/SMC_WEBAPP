(function () {
  const shell = document.querySelector(".app-shell.collapsible-shell");
  if (!shell) return;

  const sidebarToggle = document.getElementById("sidebarToggle");
  if (!sidebarToggle) return;

  const sidebarStateKey = "app_sidebar_expanded";
  const legacySidebarStateKey = "analytics_sidebar_expanded";
  const desktop = window.matchMedia("(min-width: 992px)");

  function applySidebarState(expanded) {
    shell.classList.toggle("expanded", expanded);
    sidebarToggle.setAttribute("aria-expanded", expanded ? "true" : "false");
  }

  const savedSidebarState = localStorage.getItem(sidebarStateKey) ?? localStorage.getItem(legacySidebarStateKey);
  if (localStorage.getItem(sidebarStateKey) == null && savedSidebarState != null) {
    localStorage.setItem(sidebarStateKey, savedSidebarState);
  }
  const initialExpanded = desktop.matches ? (savedSidebarState == null ? true : savedSidebarState === "1") : true;
  applySidebarState(initialExpanded);

  sidebarToggle.addEventListener("click", function () {
    const expanded = !shell.classList.contains("expanded");
    applySidebarState(expanded);
    if (desktop.matches) {
      localStorage.setItem(sidebarStateKey, expanded ? "1" : "0");
    }
  });

  desktop.addEventListener("change", function (event) {
    if (!event.matches) {
      applySidebarState(true);
      return;
    }
    const state = localStorage.getItem(sidebarStateKey);
    applySidebarState(state == null ? true : state === "1");
  });
})();
