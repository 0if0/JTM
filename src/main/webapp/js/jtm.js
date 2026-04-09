/* jtm.js — Jenkins Test Management Plugin — Progressive Enhancement Only */
'use strict';

(function () {

  // ── Progress bar widths (set via JS to avoid CSS calc issues in Jelly) ─────
  document.querySelectorAll('.jtm-progress-bar-fill[data-pct]').forEach(function (el) {
    var pct = parseFloat(el.dataset.pct) || 0;
    el.style.width = Math.min(100, Math.max(0, pct)) + '%';
  });

  // ── Mini run progress bars ─────────────────────────────────────────────────
  document.querySelectorAll('.jtm-mini-fill[data-pct]').forEach(function (el) {
    el.style.width = Math.min(100, parseFloat(el.dataset.pct) || 0) + '%';
  });

  // ── Auto-refresh dashboard every 30s (only on dashboard page) ─────────────
  if (window.location.pathname.endsWith('/jtm/') ||
      window.location.pathname.endsWith('/jtm')) {
    setTimeout(function () {
      window.location.reload();
    }, 30000);
  }

  // ── Confirm before delete actions ─────────────────────────────────────────
  document.querySelectorAll('[data-jtm-confirm]').forEach(function (el) {
    el.addEventListener('click', function (e) {
      if (!confirm(el.dataset.jtmConfirm || 'Are you sure?')) {
        e.preventDefault();
      }
    });
  });

})();
