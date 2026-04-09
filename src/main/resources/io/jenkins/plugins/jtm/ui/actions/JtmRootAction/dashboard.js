/* global jtmConfirm */
(function () {
  "use strict";

  const projectSelect = document.getElementById("jtm-dash-project");
  const deleteBtn = document.getElementById("jtm-project-delete-btn");
  const deleteForm = document.getElementById("jtm-project-delete-form");
  const deleteKey = document.getElementById("jtm-project-delete-key");
  const newBtn = document.getElementById("jtm-project-new-btn");
  const newForm = document.getElementById("jtm-project-new-form");
  const newInput = document.getElementById("jtm-project-new-input");
  const newCancel = document.getElementById("jtm-project-new-cancel");

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
        window.jtmNotify("Please select a project first.", "warning");
        return;
      }
      window.jtmConfirm(
        "Delete selected project? This works only when no test cases or runs still reference it.",
        { okText: "Delete", cancelText: "Cancel" }
      ).then((ok) => {
        if (ok) {
          deleteForm.submit();
        }
      });
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

  const runSelect = document.getElementById("jtm-run-pie-select");
  const pie = document.getElementById("jtm-run-pie-chart");
  const pct = document.getElementById("jtm-run-pie-pct");
  const sub = document.getElementById("jtm-run-pie-sub");
  const linkedEl = document.getElementById("jtm-run-pie-linked");
  const kPass = document.getElementById("jtm-run-kpi-pass");
  const kFail = document.getElementById("jtm-run-kpi-fail");
  const kBlocked = document.getElementById("jtm-run-kpi-blocked");
  const kPending = document.getElementById("jtm-run-kpi-pending");
  if (runSelect && pie && pct && sub && linkedEl && kPass && kFail && kBlocked && kPending) {
    const updatePie = () => {
      const opt = runSelect.options[runSelect.selectedIndex];
      if (!opt) {
        return;
      }
      const passed = parseInt(opt.dataset.passed || "0", 10) || 0;
      const failed = parseInt(opt.dataset.failed || "0", 10) || 0;
      const blocked = parseInt(opt.dataset.blocked || "0", 10) || 0;
      const total = parseInt(opt.dataset.total || "0", 10) || 0;
      const linked = parseInt(opt.dataset.linked || "0", 10) || 0;
      const pending = Math.max(0, total - passed - failed - blocked);
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
    updatePie();
  }

  const barWrap = document.getElementById("jtm-auto-bars");
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
})();
