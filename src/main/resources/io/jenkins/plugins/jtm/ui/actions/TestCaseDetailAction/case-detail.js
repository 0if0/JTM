/* global dialog */
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
        okText: "Delete",
      })
      .then(() => {
        form.submit();
      })
      .catch(() => {});
  });
})();
