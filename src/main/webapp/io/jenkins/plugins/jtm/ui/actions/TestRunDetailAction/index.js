(function () {
  var jtmCurrentUserId = '${it.currentUserId}';
  function collectFormBody(form) {
    var body = new URLSearchParams();
    var fd = new FormData(form);
    fd.forEach(function (value, key) {
      body.append(key, value);
    });
    return body;
  }

  function submitStepFormAjax(form, changedSel) {
    var body = new URLSearchParams();
    form.querySelectorAll('input[type="hidden"]').forEach(function (inp) {
      if (inp.name) body.append(inp.name, inp.value || '');
    });
    body.set('stepIndex', changedSel.getAttribute('data-step-index') || '0');
    body.set('stepStatus', changedSel.value || 'NOT_RUN');
    var idx = changedSel.getAttribute('data-step-index') || '0';
    var ta = form.querySelector('textarea.jtm-step-comment[data-step-index="' + idx + '"]');
    if (ta) {
      body.set('stepComment', ta.value);
    }
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', form.getAttribute('data-step-save-url') || form.action, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
      // Jenkins CSRF: many installs validate the crumb as a header on XHR (see hudson-behavior.js / window.crumb).
      if (typeof crumb !== 'undefined' && crumb && crumb.fieldName && crumb.value) {
        xhr.setRequestHeader(crumb.fieldName, crumb.value);
      }
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) return;
        if (xhr.status !== 200) {
          reject(new Error('step save failed: ' + xhr.status));
          return;
        }
        try {
          var parsed = JSON.parse(xhr.responseText || '{}');
          if (parsed && parsed.ok === true) {
            resolve();
            return;
          }
        } catch (e) {
          // Non-JSON response, treat as failure.
        }
        reject(new Error('step save returned non-ok payload'));
      };
      xhr.onerror = function () { reject(new Error('step save network error')); };
      xhr.send(body.toString());
    });
  }

  function aggregateRowStatus(row) {
    var statuses = Array.prototype.map.call(
      row.querySelectorAll('details.jtm-step-details select[name="stepStatus"]'),
      function (s) { return s.value; }
    );
    if (statuses.length === 0) return null;
    if (statuses.indexOf('FAILED') >= 0) return 'FAILED';
    if (statuses.indexOf('BLOCKED') >= 0) return 'BLOCKED';
    if (statuses.indexOf('NOT_RUN') >= 0) return 'PENDING';
    if (statuses.indexOf('FALSE_POSITIVE') >= 0) return 'FALSE_POSITIVE';
    var allPassed = statuses.every(function (s) { return s === 'PASSED'; });
    return allPassed ? 'PASSED' : 'PENDING';
  }

  function setCurrentBadge(row, status) {
    if (!status) return;
    var cell = row.querySelector('td:nth-child(2)');
    if (!cell) return;
    cell.innerHTML = '<span class="jtm-badge jtm-res--' + status + '">' + status + '</span>';
  }

  // Auto-save forms when any relevant control changes (no full page reload).
  document.querySelectorAll('.jtm-autosave-form').forEach(function (form) {
    form.querySelectorAll('select').forEach(function (sel) {
      sel.addEventListener('change', function () {
        if (form.dataset.saving === '1') return;
        if (form.dataset.formKind === 'steps') {
          // Whenever a user sets step results, the step assignment should jump to them.
          if (jtmCurrentUserId) {
            var rowForAssign = form.closest('tr.jtm-case-row');
            if (rowForAssign) {
              var assigneeSel = rowForAssign.querySelector('form[data-form-kind="result"] select[name="assignedTo"]');
              if (assigneeSel) assigneeSel.value = jtmCurrentUserId;
              var hidden = rowForAssign.querySelector('input.jtm-step-assigned-hidden');
              if (hidden) hidden.value = jtmCurrentUserId;
            }
          }
          form.dataset.saving = '1';
          // Ensure browser has committed the new select value before serialization.
          window.setTimeout(function () {
            submitStepFormAjax(form, sel).then(function () {
              form.dataset.saving = '0';
              var row = form.closest('tr.jtm-case-row');
              if (!row) return;
              var overall = aggregateRowStatus(row);
              setCurrentBadge(row, overall);
              var rs = row.querySelector('form[data-form-kind="result"] select[name="resultStatus"]');
              if (rs && overall && rs.value !== overall) rs.value = overall;
            }).catch(function () {
              form.dataset.saving = '0';
              alert('Step status could not be saved. Please retry.');
            });
          }, 0);
          return;
        }
        form.dataset.saving = '1';
        var xhr = new XMLHttpRequest();
        xhr.open((form.method || 'POST').toUpperCase(), form.action, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        if (typeof crumb !== 'undefined' && crumb && crumb.fieldName && crumb.value) {
          xhr.setRequestHeader(crumb.fieldName, crumb.value);
        }
        xhr.onreadystatechange = function () {
          if (xhr.readyState !== 4) return;
          form.dataset.saving = '0';
          if (xhr.status !== 200 && xhr.status !== 302 && xhr.status !== 0) {
            var failCaseId = form.getAttribute('data-case-id');
            if (failCaseId) localStorage.setItem('jtm-open-step-case', failCaseId);
            form.submit();
            return;
          }
          var row = form.closest('tr.jtm-case-row');
          if (!row) return;
          if (form.dataset.formKind === 'steps') {
            var overall = aggregateRowStatus(row);
            setCurrentBadge(row, overall);
            var rs = row.querySelector('form[data-form-kind="result"] select[name="resultStatus"]');
            if (rs && overall && rs.value !== overall) rs.value = overall;
          } else if (form.dataset.formKind === 'result') {
            var selected = form.querySelector('select[name="resultStatus"]');
            setCurrentBadge(row, selected ? selected.value : null);
          }
        };
        xhr.onerror = function () {
          form.dataset.saving = '0';
          var errCaseId = form.getAttribute('data-case-id');
          if (errCaseId) localStorage.setItem('jtm-open-step-case', errCaseId);
          form.submit();
        };
        xhr.send(collectFormBody(form).toString());
      });
    });
    form.querySelectorAll('textarea.jtm-case-comment').forEach(function (ta) {
      ta.addEventListener('blur', function () {
        if (form.dataset.formKind !== 'result') return;
        if (form.dataset.saving === '1') return;
        form.dataset.saving = '1';
        var xhr = new XMLHttpRequest();
        xhr.open((form.method || 'POST').toUpperCase(), form.action, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        if (typeof crumb !== 'undefined' && crumb && crumb.fieldName && crumb.value) {
          xhr.setRequestHeader(crumb.fieldName, crumb.value);
        }
        xhr.onreadystatechange = function () {
          if (xhr.readyState !== 4) return;
          form.dataset.saving = '0';
          if (xhr.status !== 200 && xhr.status !== 302 && xhr.status !== 0) {
            var failCaseId = form.getAttribute('data-case-id');
            if (failCaseId) localStorage.setItem('jtm-open-step-case', failCaseId);
            form.submit();
            return;
          }
          var row = form.closest('tr.jtm-case-row');
          if (!row) return;
          var selected = form.querySelector('select[name="resultStatus"]');
          setCurrentBadge(row, selected ? selected.value : null);
        };
        xhr.onerror = function () {
          form.dataset.saving = '0';
          var errCaseId = form.getAttribute('data-case-id');
          if (errCaseId) localStorage.setItem('jtm-open-step-case', errCaseId);
          form.submit();
        };
        xhr.send(collectFormBody(form).toString());
      });
    });
    form.querySelectorAll('textarea.jtm-step-comment').forEach(function (ta) {
      ta.addEventListener('blur', function () {
        if (form.dataset.formKind !== 'steps') return;
        if (form.dataset.saving === '1') return;
        var idx = ta.getAttribute('data-step-index');
        var sel = form.querySelector('select[name="stepStatus"][data-step-index="' + idx + '"]');
        if (!sel) return;
        form.dataset.saving = '1';
        window.setTimeout(function () {
          submitStepFormAjax(form, sel).then(function () {
            form.dataset.saving = '0';
            var row = form.closest('tr.jtm-case-row');
            if (!row) return;
            var overall = aggregateRowStatus(row);
            setCurrentBadge(row, overall);
            var rs = row.querySelector('form[data-form-kind="result"] select[name="resultStatus"]');
            if (rs && overall && rs.value !== overall) rs.value = overall;
          }).catch(function () {
            form.dataset.saving = '0';
            alert('Step comment could not be saved. Please retry.');
          });
        }, 0);
      });
    });
  });

  // Restore previously open step details after fallback full submit.
  var reopenCase = localStorage.getItem('jtm-open-step-case');
  if (reopenCase) {
    var row = document.querySelector('tr.jtm-case-row[data-case-id="' + reopenCase + '"]');
    if (row) {
      var det = row.querySelector('details.jtm-step-details');
      if (det) det.open = true;
    }
    localStorage.removeItem('jtm-open-step-case');
  }

  // Select-all for "Add more test cases to this run".
  var all = document.getElementById('jtm-linked-pick-all');
  if (all) {
    all.addEventListener('change', function () {
      var checks = document.querySelectorAll('input[name="linkedTestCaseId"]');
      checks.forEach(function (c) { c.checked = all.checked; });
    });
  }

  // Keep step hidden assignee synced with row assignee dropdown.
  document.querySelectorAll('tr.jtm-case-row').forEach(function (row) {
    var assigneeSel = row.querySelector('form[data-form-kind="result"] select[name="assignedTo"]');
    var hidden = row.querySelector('input.jtm-step-assigned-hidden');
    if (!assigneeSel || !hidden) return;
    var sync = function () { hidden.value = assigneeSel.value || ''; };
    sync();
    assigneeSel.addEventListener('change', sync);
  });
})();

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
