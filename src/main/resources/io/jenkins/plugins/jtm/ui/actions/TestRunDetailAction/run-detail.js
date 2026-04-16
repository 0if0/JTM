/* global crumb, dialog, notificationBar */
(function () {
  "use strict";

  const cfg = document.getElementById("jtm-run-detail-config");
  const jtmCurrentUserId = cfg && cfg.getAttribute("data-current-user-id") ? cfg.getAttribute("data-current-user-id") : "";

  function collectFormBody(form) {
    const body = new URLSearchParams();
    const fd = new FormData(form);
    fd.forEach((value, key) => {
      body.append(key, value);
    });
    return body;
  }

  function submitStepFormAjax(form, changedSel) {
    const body = new URLSearchParams();
    form.querySelectorAll('input[type="hidden"]').forEach((inp) => {
      if (inp.name) {
        body.append(inp.name, inp.value || "");
      }
    });
    body.set("stepIndex", changedSel.getAttribute("data-step-index") || "0");
    body.set("stepStatus", changedSel.value || "NOT_RUN");
    const idx = changedSel.getAttribute("data-step-index") || "0";
    const ta = form.querySelector('textarea.jtm-step-comment[data-step-index="' + idx + '"]');
    if (ta) {
      body.set("stepComment", ta.value);
    }
    const url = form.getAttribute("data-step-save-url") || form.action;
    return fetch(url, {
      method: "POST",
      headers: crumb.wrap({
        "Content-Type": "application/x-www-form-urlencoded",
        "X-Requested-With": "XMLHttpRequest",
      }),
      body: body.toString(),
    }).then((rsp) => {
      if (!rsp.ok) {
        throw new Error("step save failed: " + rsp.status);
      }
      return rsp.json();
    }).then((parsed) => {
      if (parsed && parsed.ok === true) {
        return;
      }
      throw new Error("step save returned non-ok payload");
    });
  }

  function aggregateRowStatus(row) {
    const statuses = Array.prototype.map.call(
      row.querySelectorAll('details.jtm-step-details select[name="stepStatus"]'),
      (s) => s.value
    );
    if (statuses.length === 0) {
      return null;
    }
    if (statuses.indexOf("FAILED") >= 0) {
      return "FAILED";
    }
    if (statuses.indexOf("BLOCKED") >= 0) {
      return "BLOCKED";
    }
    if (statuses.indexOf("NOT_RUN") >= 0) {
      return "PENDING";
    }
    if (statuses.indexOf("FALSE_POSITIVE") >= 0) {
      return "FALSE_POSITIVE";
    }
    const allPassed = statuses.every((s) => s === "PASSED");
    return allPassed ? "PASSED" : "PENDING";
  }

  function setCurrentBadge(row, status) {
    if (!status) {
      return;
    }
    const cell = row.querySelector("td:nth-child(2)");
    if (!cell) {
      return;
    }
    cell.innerHTML = '<span class="jtm-badge jtm-res--' + status + '">' + status + "</span>";
  }

  function postAutosaveForm(form) {
    const method = (form.method || "POST").toUpperCase();
    return fetch(form.action, {
      method,
      headers: crumb.wrap({
        "Content-Type": "application/x-www-form-urlencoded",
      }),
      body: collectFormBody(form).toString(),
    });
  }

  document.querySelectorAll(".jtm-autosave-form").forEach((form) => {
    form.querySelectorAll("select").forEach((sel) => {
      sel.addEventListener("change", () => {
        if (form.dataset.saving === "1") {
          return;
        }
        if (form.dataset.formKind === "steps") {
          if (jtmCurrentUserId) {
            const rowForAssign = form.closest("tr.jtm-case-row");
            if (rowForAssign) {
              const assigneeSel = rowForAssign.querySelector('form[data-form-kind="result"] select[name="assignedTo"]');
              if (assigneeSel) {
                assigneeSel.value = jtmCurrentUserId;
              }
              const hidden = rowForAssign.querySelector("input.jtm-step-assigned-hidden");
              if (hidden) {
                hidden.value = jtmCurrentUserId;
              }
            }
          }
          form.dataset.saving = "1";
          window.setTimeout(() => {
            submitStepFormAjax(form, sel).then(() => {
              form.dataset.saving = "0";
              const row = form.closest("tr.jtm-case-row");
              if (!row) {
                return;
              }
              const overall = aggregateRowStatus(row);
              setCurrentBadge(row, overall);
              const rs = row.querySelector('form[data-form-kind="result"] select[name="resultStatus"]');
              if (rs && overall && rs.value !== overall) {
                rs.value = overall;
              }
            }).catch(() => {
              form.dataset.saving = "0";
              notificationBar.show("Step status could not be saved. Please retry.", notificationBar.ERROR);
            });
          }, 0);
          return;
        }
        form.dataset.saving = "1";
        postAutosaveForm(form).then((rsp) => {
          form.dataset.saving = "0";
          if (rsp.status !== 200 && rsp.status !== 302 && rsp.status !== 0) {
            const failCaseId = form.getAttribute("data-case-id");
            if (failCaseId) {
              localStorage.setItem("jtm-open-step-case", failCaseId);
            }
            form.submit();
            return;
          }
          const row = form.closest("tr.jtm-case-row");
          if (!row) {
            return;
          }
          if (form.dataset.formKind === "steps") {
            const overall = aggregateRowStatus(row);
            setCurrentBadge(row, overall);
            const rs = row.querySelector('form[data-form-kind="result"] select[name="resultStatus"]');
            if (rs && overall && rs.value !== overall) {
              rs.value = overall;
            }
          } else if (form.dataset.formKind === "result") {
            const selected = form.querySelector('select[name="resultStatus"]');
            setCurrentBadge(row, selected ? selected.value : null);
          }
        }).catch(() => {
          form.dataset.saving = "0";
          const errCaseId = form.getAttribute("data-case-id");
          if (errCaseId) {
            localStorage.setItem("jtm-open-step-case", errCaseId);
          }
          form.submit();
        });
      });
    });
    form.querySelectorAll("textarea.jtm-case-comment").forEach((ta) => {
      ta.addEventListener("blur", () => {
        if (form.dataset.formKind !== "result") {
          return;
        }
        if (form.dataset.saving === "1") {
          return;
        }
        form.dataset.saving = "1";
        postAutosaveForm(form).then((rsp) => {
          form.dataset.saving = "0";
          if (rsp.status !== 200 && rsp.status !== 302 && rsp.status !== 0) {
            const failCaseId = form.getAttribute("data-case-id");
            if (failCaseId) {
              localStorage.setItem("jtm-open-step-case", failCaseId);
            }
            form.submit();
            return;
          }
          const row = form.closest("tr.jtm-case-row");
          if (!row) {
            return;
          }
          const selected = form.querySelector('select[name="resultStatus"]');
          setCurrentBadge(row, selected ? selected.value : null);
        }).catch(() => {
          form.dataset.saving = "0";
          const errCaseId = form.getAttribute("data-case-id");
          if (errCaseId) {
            localStorage.setItem("jtm-open-step-case", errCaseId);
          }
          form.submit();
        });
      });
    });
    form.querySelectorAll("textarea.jtm-step-comment").forEach((ta) => {
      ta.addEventListener("blur", () => {
        if (form.dataset.formKind !== "steps") {
          return;
        }
        if (form.dataset.saving === "1") {
          return;
        }
        const idx = ta.getAttribute("data-step-index");
        const stepSel = form.querySelector('select[name="stepStatus"][data-step-index="' + idx + '"]');
        if (!stepSel) {
          return;
        }
        form.dataset.saving = "1";
        window.setTimeout(() => {
          submitStepFormAjax(form, stepSel).then(() => {
            form.dataset.saving = "0";
            const row = form.closest("tr.jtm-case-row");
            if (!row) {
              return;
            }
            const overall = aggregateRowStatus(row);
            setCurrentBadge(row, overall);
            const rs = row.querySelector('form[data-form-kind="result"] select[name="resultStatus"]');
            if (rs && overall && rs.value !== overall) {
              rs.value = overall;
            }
          }).catch(() => {
            form.dataset.saving = "0";
            notificationBar.show("Step comment could not be saved. Please retry.", notificationBar.ERROR);
          });
        }, 0);
      });
    });
  });

  const reopenCase = localStorage.getItem("jtm-open-step-case");
  if (reopenCase) {
    const row = document.querySelector('tr.jtm-case-row[data-case-id="' + reopenCase + '"]');
    if (row) {
      const det = row.querySelector("details.jtm-step-details");
      if (det) {
        det.open = true;
      }
    }
    localStorage.removeItem("jtm-open-step-case");
  }

  const all = document.getElementById("jtm-linked-pick-all");
  if (all) {
    all.addEventListener("change", () => {
      document.querySelectorAll('input[name="linkedTestCaseId"]').forEach((c) => {
        c.checked = all.checked;
      });
    });
  }

  document.querySelectorAll("tr.jtm-case-row").forEach((row) => {
    const assigneeSel = row.querySelector('form[data-form-kind="result"] select[name="assignedTo"]');
    const hidden = row.querySelector("input.jtm-step-assigned-hidden");
    if (!assigneeSel || !hidden) {
      return;
    }
    const sync = () => {
      hidden.value = assigneeSel.value || "";
    };
    sync();
    assigneeSel.addEventListener("change", sync);
  });
})();

(function () {
  "use strict";

  document.addEventListener("submit", (event) => {
    const form = event.target;
    if (!form || !form.classList || !form.classList.contains("jtm-confirm-submit")) {
      return;
    }
    const message = form.getAttribute("data-confirm-message") || "Are you sure?";
    event.preventDefault();
    dialog
      .confirm(message, {
        okText: "OK",
      })
      .then(() => {
        form.submit();
      })
      .catch(() => {});
  });
})();
