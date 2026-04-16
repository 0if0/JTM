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

  const initDashboard = () => {
    const projectSelect = visibleById("jtm-dash-project");
    const deleteBtn = visibleById("jtm-project-delete-btn");
    const deleteForm = visibleById("jtm-project-delete-form");
    const deleteKey = visibleById("jtm-project-delete-key");
    const newBtn = visibleById("jtm-project-new-btn");
    const newForm = visibleById("jtm-project-new-form");
    const newInput = visibleById("jtm-project-new-input");
    const newCancel = visibleById("jtm-project-new-cancel");

    if (projectSelect) {
      if (deleteKey) {
        deleteKey.value = projectSelect.value || "";
      }
      projectSelect.addEventListener("change", () => {
        if (deleteKey) {
          deleteKey.value = projectSelect.value || "";
        }
        if (projectSelect.form) {
          projectSelect.form.submit();
        }
      });
    }

    if (deleteBtn && deleteForm && deleteKey) {
      deleteBtn.addEventListener("click", () => {
        if (!deleteKey.value) {
          notificationBar.show("Please select a project first.", notificationBar.WARNING);
          return;
        }
        if (window.confirm("Delete selected project? This works only when no test cases or runs still reference it.")) {
          deleteForm.submit();
        }
      });
    }

    if (newBtn && newForm && newInput) {
      newBtn.addEventListener("click", () => {
        const show = newForm.style.display === "none" || newForm.style.display === "";
        newForm.style.display = show ? "flex" : "none";
        if (show) {
          window.setTimeout(() => newInput.focus(), 0);
        }
      });
    }

    if (newCancel && newForm) {
      newCancel.addEventListener("click", () => {
        newForm.style.display = "none";
      });
    }

    const runSelect = visibleById("jtm-run-pie-select");
    const pie = visibleById("jtm-run-pie-chart");
    const pct = visibleById("jtm-run-pie-pct");
    const sub = visibleById("jtm-run-pie-sub");
    const linkedEl = visibleById("jtm-run-pie-linked");
    const kPass = visibleById("jtm-run-kpi-pass");
    const kFail = visibleById("jtm-run-kpi-fail");
    const kBlocked = visibleById("jtm-run-kpi-blocked");
    const kPending = visibleById("jtm-run-kpi-pending");
    if (runSelect && pie && pct && sub && linkedEl && kPass && kFail && kBlocked && kPending) {
      let lastRunValue = "";
      const updatePie = () => {
        lastRunValue = runSelect.value || "";
        const opt = runSelect.options[runSelect.selectedIndex];
        if (!opt) {
          return;
        }
        const passed = parseInt(opt.dataset.passed || "0", 10) || 0;
        const failed = parseInt(opt.dataset.failed || "0", 10) || 0;
        const blocked = parseInt(opt.dataset.blocked || "0", 10) || 0;
        const falsePositive = parseInt(opt.dataset.falsePositive || "0", 10) || 0;
        const skipped = parseInt(opt.dataset.skipped || "0", 10) || 0;
        const total = parseInt(opt.dataset.total || "0", 10) || 0;
        const linked = parseInt(opt.dataset.linked || "0", 10) || 0;
        const pending = Math.max(0, total - passed - failed - blocked - falsePositive - skipped);
        const passPct = total > 0 ? passed / total : 0;
        const failPct = total > 0 ? failed / total : 0;
        const blockedPct = total > 0 ? blocked / total : 0;
        pie.style.setProperty("--pie-pass", passPct * 360 + "deg");
        pie.style.setProperty("--pie-fail", failPct * 360 + "deg");
        pie.style.setProperty("--pie-blocked", blockedPct * 360 + "deg");
        pct.textContent = (passPct * 100).toFixed(1) + "%";
        sub.textContent = passed + " / " + total;
        linkedEl.textContent = "Linked: " + linked;
        kPass.textContent = String(passed);
        kFail.textContent = String(failed);
        kBlocked.textContent = String(blocked);
        kPending.textContent = String(pending);
      };
      runSelect.addEventListener("change", updatePie);
      runSelect.addEventListener("input", updatePie);
      // Jenkins custom select UIs sometimes change value without dispatching `change`.
      ["click", "keyup", "blur"].forEach((evt) => {
        runSelect.addEventListener(evt, () => {
          const v = runSelect.value || "";
          if (v !== lastRunValue) {
            updatePie();
          }
        });
      });
      updatePie();
    }

    const barWrap = visibleById("jtm-auto-bars");
    if (barWrap) {
      const rows = Array.from(barWrap.querySelectorAll(".jtm-bar-row"));
      const ms = rows.map((r) => parseInt(r.getAttribute("data-ms") || "0", 10) || 0);
      const max = Math.max(0, ...ms);
      rows.forEach((r) => {
        const v = parseInt(r.getAttribute("data-ms") || "0", 10) || 0;
        const p = max > 0 ? (v / max) * 100 : 0;
        const fill = r.querySelector(".jtm-bar-fill");
        if (fill) {
          fill.style.width = p.toFixed(2) + "%";
        }
      });
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initDashboard, { once: true });
  } else {
    window.setTimeout(initDashboard, 0);
  }
})();
