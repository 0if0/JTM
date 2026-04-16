/* global dialog, notificationBar */
(function () {
  "use strict";

  const sel = document.getElementById("jtm-runs-project");
  if (sel && sel.form) {
    sel.addEventListener("change", () => {
      sel.form.submit();
    });
  }
  const all = document.getElementById("jtm-runs-select-all");
  if (all) {
    all.addEventListener("change", () => {
      const on = all.checked;
      document.querySelectorAll(".jtm-runs-row-check").forEach((cb) => {
        cb.checked = on;
      });
    });
  }
  const batchForm = document.getElementById("jtm-runs-batch-export");
  const delBtn = document.getElementById("jtm-runs-delete-selected");
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
      dialog
        .confirm("Delete selected test runs permanently?", {
          okText: "Delete",
        })
        .then(() => {
          const oldAction = batchForm.action;
          const oldMethod = batchForm.method;
          const oldFormat = document.getElementById("jtm-runs-export-format");
          const oldDisabled = oldFormat ? oldFormat.disabled : false;
          batchForm.action = oldAction.replace("/exportBatch", "/deleteBatch");
          batchForm.method = "post";
          if (oldFormat) {
            oldFormat.disabled = true;
          }
          batchForm.submit();
          batchForm.action = oldAction;
          batchForm.method = oldMethod;
          if (oldFormat) {
            oldFormat.disabled = oldDisabled;
          }
        })
        .catch(() => {});
    });
  }
})();
