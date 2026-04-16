/* global dialog, notificationBar */
(function () {
  "use strict";

  const visibleById = (id) => {
    const nodes = document.querySelectorAll("#" + id);
    for (const n of nodes) {
      if (n && n.offsetParent !== null) {
        return n;
      }
    }
    return nodes.length > 0 ? nodes[0] : null;
  };

  const initCasesIndex = () => {
    const form = visibleById("jtm-import-form");
    const input = visibleById("jtm-import-file");
    const payload = visibleById("jtm-import-json");
    const name = visibleById("jtm-import-file-name");
    if (form && input && payload && name) {
      input.addEventListener("change", () => {
        const file = input.files && input.files[0];
        if (!file) {
          return;
        }
        const r = new FileReader();
        r.onload = () => {
          try {
            const bytes = new Uint8Array(r.result);
            let decoded = "";
            try {
              decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
            } catch (utfErr) {
              decoded = new TextDecoder("windows-1252").decode(bytes);
            }
            payload.value = decoded;
            name.value = file.name || "";
          } catch (e) {
            notificationBar.show("Could not decode file for import.", notificationBar.ERROR);
          }
        };
        r.onerror = () => {
          notificationBar.show("Could not read file for import.", notificationBar.ERROR);
        };
        r.readAsArrayBuffer(file);
      });

      form.addEventListener("submit", (e) => {
        const file = input.files && input.files[0];
        if (!file) {
          return;
        }
        if (payload.value && payload.value.length > 0) {
          return;
        }
        e.preventDefault();
        const r = new FileReader();
        r.onload = () => {
          try {
            const bytes = new Uint8Array(r.result);
            let decoded = "";
            try {
              decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
            } catch (utfErr) {
              decoded = new TextDecoder("windows-1252").decode(bytes);
            }
            payload.value = decoded;
            name.value = file.name || "";
            form.submit();
          } catch (err) {
            notificationBar.show("Could not decode file for import.", notificationBar.ERROR);
          }
        };
        r.onerror = () => {
          notificationBar.show("Could not read file for import.", notificationBar.ERROR);
        };
        r.readAsArrayBuffer(file);
      });
    }

    const proj = visibleById("jtm-filter-project");
    if (proj && proj.form) {
      proj.addEventListener("change", () => {
        if (proj.form) {
          proj.form.submit();
        }
      });
    }

    const selectAll = visibleById("jtm-tc-select-all");
    if (selectAll) {
      selectAll.addEventListener("change", () => {
        document.querySelectorAll(".jtm-tc-row-check").forEach((cb) => {
          cb.checked = selectAll.checked;
        });
      });
    }

    const bulkForm = visibleById("jtm-bulk-delete-form");
    if (bulkForm) {
      bulkForm.addEventListener("submit", (ev) => {
        const checked = Array.from(document.querySelectorAll(".jtm-tc-row-check")).filter((cb) => cb.checked).length;
        if (checked === 0) {
          ev.preventDefault();
          notificationBar.show("Select at least one test case.", notificationBar.WARNING);
          return;
        }
        ev.preventDefault();
        if (window.confirm("Delete selected test cases permanently?")) {
          bulkForm.submit();
        }
      });
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initCasesIndex, { once: true });
  } else {
    window.setTimeout(initCasesIndex, 0);
  }
})();
