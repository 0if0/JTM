/* global document */
(function () {
  "use strict";

  const initCaseEdit = () => {
    const tb = document.getElementById("jtm-step-rows");
    if (!tb) {
      return;
    }

    function renumber() {
      const rows = tb.querySelectorAll(".jtm-step-row");
      for (let i = 0; i < rows.length; i++) {
        const num = rows[i].querySelector(".jtm-step-num");
        if (num) {
          num.textContent = String(i + 1);
        }
      }
    }

    tb.addEventListener("click", (e) => {
      const btn = e.target && e.target.closest && e.target.closest(".jtm-step-del");
      if (!btn) {
        return;
      }
      const tr = btn.closest(".jtm-step-row");
      if (!tr || tb.querySelectorAll(".jtm-step-row").length <= 1) {
        return;
      }
      tr.remove();
      renumber();
    });

    tb.addEventListener("click", (e) => {
      const addBtn = e.target && e.target.closest && e.target.closest(".jtm-step-add-btn");
      if (!addBtn) {
        return;
      }
      const first = tb.querySelector(".jtm-step-row");
      if (!first) {
        return;
      }
      const clone = first.cloneNode(true);
      clone.querySelectorAll("input").forEach((inp) => {
        inp.value = "";
      });
      tb.appendChild(clone);
      renumber();
    });
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initCaseEdit, { once: true });
  } else {
    window.setTimeout(initCaseEdit, 0);
  }
})();
