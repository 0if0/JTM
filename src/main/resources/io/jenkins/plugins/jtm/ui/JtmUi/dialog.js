/* global crumb */
(function () {
  "use strict";

  function ensureConfirmDialog() {
    let dlg = document.getElementById("jtm-shared-confirm");
    if (dlg) {
      return dlg;
    }
    dlg = document.createElement("dialog");
    dlg.id = "jtm-shared-confirm";
    dlg.className = "jenkins-dialog";
    dlg.innerHTML =
      '<div class="jenkins-dialog__wrapper">' +
      '<div class="jenkins-dialog__contents">' +
      '<p class="jenkins-dialog__message jtm-confirm-msg"></p>' +
      '<div class="jenkins-dialog__footer">' +
      '<button type="button" class="jenkins-button jtm-confirm-cancel"></button>' +
      '<button type="button" class="jenkins-button jenkins-button--primary jtm-confirm-ok"></button>' +
      "</div></div></div>";
    document.body.appendChild(dlg);
    return dlg;
  }

  /**
   * @param {string} message
   * @param {{ okText?: string, cancelText?: string }} [labels]
   * @returns {Promise<boolean>}
   */
  window.jtmConfirm = function (message, labels) {
    const okText = (labels && labels.okText) || "OK";
    const cancelText = (labels && labels.cancelText) || "Cancel";
    return new Promise((resolve) => {
      const dlg = ensureConfirmDialog();
      const msgEl = dlg.querySelector(".jtm-confirm-msg");
      const okBtn = dlg.querySelector(".jtm-confirm-ok");
      const cancelBtn = dlg.querySelector(".jtm-confirm-cancel");
      msgEl.textContent = message;
      okBtn.textContent = okText;
      cancelBtn.textContent = cancelText;

      const finish = (value) => {
        dlg.removeEventListener("cancel", onEsc);
        okBtn.removeEventListener("click", onOk);
        cancelBtn.removeEventListener("click", onCancel);
        dlg.close();
        resolve(value);
      };
      const onOk = () => finish(true);
      const onCancel = () => finish(false);
      const onEsc = (e) => {
        e.preventDefault();
        finish(false);
      };
      okBtn.addEventListener("click", onOk);
      cancelBtn.addEventListener("click", onCancel);
      dlg.addEventListener("cancel", onEsc);
      dlg.showModal();
    });
  };

  /**
   * @param {string} message
   * @param {"info"|"warning"|"error"|"success"} [kind]
   */
  window.jtmNotify = function (message, kind) {
    const k = kind || "warning";
    const map = {
      info: "jenkins-alert-info",
      warning: "jenkins-alert-warning",
      error: "jenkins-alert-error",
      success: "jenkins-alert-success",
    };
    const wrap = document.createElement("div");
    wrap.className = "jenkins-alert " + (map[k] || map.warning) + " jtm-toast";
    wrap.setAttribute("role", "status");
    wrap.textContent = message;
    wrap.style.cssText = "position:fixed;top:1rem;right:1rem;z-index:10000;max-width:28rem;";
    document.body.appendChild(wrap);
    window.setTimeout(() => {
      wrap.remove();
    }, 6000);
  };
})();
