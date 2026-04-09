(function () {
  var all = document.getElementById('jtm-newrun-linked-all');
  var filterSel = document.getElementById('jtm-newrun-type-filter');

  function rowVisible(row, mode) {
    var t = (row.getAttribute('data-jtm-type') || '').toUpperCase();
    if (mode === 'manual') {
      return t === 'MANUAL' || t === 'EXPLORATORY';
    }
    if (mode === 'automated') {
      return t === 'AUTOMATED';
    }
    return true;
  }

  function applyTypeFilter() {
    var mode = filterSel && filterSel.value ? filterSel.value : 'all';
    var rows = document.querySelectorAll('.jtm-newrun-pick__row');
    rows.forEach(function (row) {
      var show = rowVisible(row, mode);
      row.style.display = show ? '' : 'none';
      if (!show) {
        var cb = row.querySelector('input[type="checkbox"]');
        if (cb) cb.checked = false;
      }
    });
    if (all) all.checked = false;
  }

  if (filterSel) {
    filterSel.addEventListener('change', applyTypeFilter);
  }

  if (all) {
    all.addEventListener('change', function () {
      var rows = document.querySelectorAll('.jtm-newrun-pick__row');
      rows.forEach(function (row) {
        if (row.style.display === 'none') return;
        var cb = row.querySelector('input[type="checkbox"]');
        if (cb) cb.checked = all.checked;
      });
    });
  }
})();
