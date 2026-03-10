(function () {
  const shell = document.querySelector(".app-shell.collapsible-shell");
  if (!shell) return;

  const desktop = window.matchMedia("(min-width: 992px)");
  const sidebar = shell.querySelector(".sidebar");
  const EDGE_TRIGGER_WIDTH = 14;
  const CLOSE_TRIGGER_WIDTH = 320;
  let isExpanded = false;

  function syncSidebarClass(expanded) {
    if (!sidebar) return;
    sidebar.classList.toggle("collapsed", !expanded && desktop.matches);
  }

  function setExpanded(nextExpanded) {
    if (isExpanded === nextExpanded) return;
    isExpanded = nextExpanded;
    shell.classList.toggle("expanded", nextExpanded);
    syncSidebarClass(nextExpanded);
  }

  function applyDesktopMode() {
    if (desktop.matches) {
      shell.classList.add("hover-expand");
      setExpanded(false);
    } else {
      shell.classList.remove("hover-expand");
      setExpanded(true);
    }
  }

  function handlePointerMove(event) {
    if (!desktop.matches) return;

    if (event.clientX <= EDGE_TRIGGER_WIDTH) {
      setExpanded(true);
      return;
    }

    // Use a wider close threshold to prevent rapid open/close jitter near the left edge.
    if (isExpanded && sidebar && !sidebar.matches(":hover") && event.clientX >= CLOSE_TRIGGER_WIDTH) {
      setExpanded(false);
    }
  }

  applyDesktopMode();

  if (sidebar) {
    sidebar.addEventListener("mouseenter", function () {
      if (!desktop.matches) return;
      setExpanded(true);
    });

    sidebar.addEventListener("mouseleave", function () {
      if (!desktop.matches) return;
      setExpanded(false);
    });
  }

  document.addEventListener("mousemove", handlePointerMove);

  desktop.addEventListener("change", function () {
    applyDesktopMode();
  });
})();
