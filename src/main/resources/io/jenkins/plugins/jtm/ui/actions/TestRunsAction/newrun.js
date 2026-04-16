(function () {
  "use strict";

  const initNewRun = () => {
    const all = document.getElementById("jtm-newrun-linked-all");
    const filterSel = document.getElementById("jtm-newrun-type-filter");

    function rowVisible(row, mode) {
      const t = (row.getAttribute("data-jtm-type") || "").toUpperCase();
      if (mode === "manual") {
        return t === "MANUAL" || t === "EXPLORATORY";
      }
      if (mode === "automated") {
        return t === "AUTOMATED";
      }
      return true;
    }

    function applyTypeFilter() {
      const mode = filterSel && filterSel.value ? filterSel.value : "all";
      const rows = document.querySelectorAll(".jtm-newrun-pick__row");
      rows.forEach((row) => {
        const show = rowVisible(row, mode);
        row.style.display = show ? "" : "none";
        if (!show) {
          const cb = row.querySelector('input[type="checkbox"]');
          if (cb) {
            cb.checked = false;
          }
        }
      });
      if (all) {
        all.checked = false;
      }
    }

    if (filterSel) {
      filterSel.addEventListener("change", applyTypeFilter);
    }

    if (all) {
      all.addEventListener("change", () => {
        const rows = document.querySelectorAll(".jtm-newrun-pick__row");
        rows.forEach((row) => {
          if (row.style.display === "none") {
            return;
          }
          const cb = row.querySelector('input[type="checkbox"]');
          if (cb) {
            cb.checked = all.checked;
          }
        });
      });
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initNewRun, { once: true });
  } else {
    window.setTimeout(initNewRun, 0);
  }
})();
