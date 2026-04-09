(function () {
  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || !form.classList || !form.classList.contains('jtm-confirm-submit')) {
      return;
    }
    var message = form.getAttribute('data-confirm-message') || 'Are you sure?';
    if (!window.confirm(message)) {
      event.preventDefault();
    }
  });
})();
