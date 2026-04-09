(function () {
  var form = document.getElementById('jtm-import-form');
  var input = document.getElementById('jtm-import-file');
  var payload = document.getElementById('jtm-import-json');
  var name = document.getElementById('jtm-import-file-name');
  if (!form || !input || !payload || !name) return;
  form.addEventListener('submit', function (e) {
    var file = input.files && input.files[0];
    if (!file) return;
    if (payload.value && payload.value.length > 0) return;
    e.preventDefault();
    var r = new FileReader();
    r.onload = function () {
      try {
        var bytes = new Uint8Array(r.result);
        var decoded = '';
        try {
          decoded = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
        } catch (utfErr) {
          decoded = new TextDecoder('windows-1252').decode(bytes);
        }
        payload.value = decoded;
        name.value = file.name || '';
        form.submit();
      } catch (err) {
        alert('Could not decode file for import.');
      }
    };
    r.onerror = function () {
      alert('Could not read file for import.');
    };
    r.readAsArrayBuffer(file);
  });
  var proj = document.getElementById('jtm-filter-project');
  if (proj && proj.form) {
    proj.addEventListener('change', function () { proj.form.submit(); });
  }
  var selectAll = document.getElementById('jtm-tc-select-all');
  if (selectAll) {
    selectAll.addEventListener('change', function () {
      document.querySelectorAll('.jtm-tc-row-check').forEach(function (cb) { cb.checked = selectAll.checked; });
    });
  }
  var bulkForm = document.getElementById('jtm-bulk-delete-form');
  if (bulkForm) {
    bulkForm.addEventListener('submit', function (ev) {
      var checked = Array.from(document.querySelectorAll('.jtm-tc-row-check')).filter(function (cb) { return cb.checked; }).length;
      if (checked === 0) {
        ev.preventDefault();
        alert('Bitte mindestens einen Test Case auswaehlen.');
        return;
      }
      if (!confirm('Ausgewaehlte Test Cases wirklich loeschen?')) {
        ev.preventDefault();
      }
    });
  }
})();
