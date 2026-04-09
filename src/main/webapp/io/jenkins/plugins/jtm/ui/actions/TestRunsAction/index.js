(function () {
  var sel = document.getElementById('jtm-runs-project');
  if (sel && sel.form) {
    sel.addEventListener('change', function () { sel.form.submit(); });
  }
  var all = document.getElementById('jtm-runs-select-all');
  if (all) {
    all.addEventListener('change', function () {
      var on = all.checked;
      document.querySelectorAll('.jtm-runs-row-check').forEach(function (cb) { cb.checked = on; });
    });
  }
  var batchForm = document.getElementById('jtm-runs-batch-export');
  var delBtn = document.getElementById('jtm-runs-delete-selected');
  if (batchForm) {
    batchForm.addEventListener('submit', function (ev) {
      var any = false;
      document.querySelectorAll('.jtm-runs-row-check').forEach(function (cb) {
        if (cb.checked) any = true;
      });
      if (!any) {
        ev.preventDefault();
        alert('Select at least one test run to export.');
      }
    });
  }
  if (batchForm && delBtn) {
    delBtn.addEventListener('click', function () {
      var any = false;
      document.querySelectorAll('.jtm-runs-row-check').forEach(function (cb) {
        if (cb.checked) any = true;
      });
      if (!any) {
        alert('Select at least one test run to delete.');
        return;
      }
      if (!confirm('Delete selected test runs permanently?')) {
        return;
      }
      var oldAction = batchForm.action;
      var oldMethod = batchForm.method;
      var oldFormat = document.getElementById('jtm-runs-export-format');
      var oldDisabled = oldFormat ? oldFormat.disabled : false;
      batchForm.action = oldAction.replace('/exportBatch', '/deleteBatch');
      batchForm.method = 'post';
      if (oldFormat) {
        oldFormat.disabled = true;
      }
      batchForm.submit();
      batchForm.action = oldAction;
      batchForm.method = oldMethod;
      if (oldFormat) {
        oldFormat.disabled = oldDisabled;
      }
    });
  }
})();
