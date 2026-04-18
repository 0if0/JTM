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

  const initRunsIndex = () => {
    const all = visibleById("jtm-runs-select-all");
    if (all) {
      all.addEventListener("change", () => {
        const on = all.checked;
        document.querySelectorAll(".jtm-runs-row-check").forEach((cb) => {
          cb.checked = on;
        });
      });
    }

    const batchForm = visibleById("jtm-runs-batch-export");
    const delBtn = visibleById("jtm-runs-delete-selected");
    if (batchForm) {
      batchForm.addEventListener("submit", (ev) => {
        let any = false;
        document.querySelectorAll(".jtm-runs-row-check").forEach((cb) => {
          if (cb.checked) {
            any = true;
          }
        });
        if (!any) {
          ev.preventDefault();
          notificationBar.show("Select at least one test run to export.", notificationBar.WARNING);
        }
      });
    }

    if (batchForm && delBtn) {
      delBtn.addEventListener("click", () => {
        let any = false;
        document.querySelectorAll(".jtm-runs-row-check").forEach((cb) => {
          if (cb.checked) {
            any = true;
          }
        });
        if (!any) {
          notificationBar.show("Select at least one test run to delete.", notificationBar.WARNING);
          return;
        }
        if (!window.confirm("Delete selected test runs permanently?")) {
          return;
        }
        const oldFormat = visibleById("jtm-runs-export-format");
        const oldDisabled = oldFormat ? oldFormat.disabled : false;
        batchForm.action = batchForm.action.replace("/exportBatch", "/deleteBatch");
        batchForm.method = "post";
        if (oldFormat) {
          oldFormat.disabled = true;
        }
        batchForm.submit();
        window.setTimeout(() => {
          if (oldFormat) {
            oldFormat.disabled = oldDisabled;
          }
        }, 0);
      });
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initRunsIndex, { once: true });
  } else {
    window.setTimeout(initRunsIndex, 0);
  }
})();
