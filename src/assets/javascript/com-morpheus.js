/* com-morpheus: browser side of the HPE COM tab: action buttons (phase 2) and route diagnostics.
 * Loaded on every page by ComUiLoaderProvider while actions or route diagnostics are on. The tab arrives
 * later (AJAX), so this script watches for it and uses one delegated click handler.
 * POSTs carry core's CSRF token (Spring Security): from this page's <meta name="_csrf">, or, when the
 * page has none, from a core page fetched once. Actions only start COM jobs the server re-checks
 * (permission, tenant, host match); diagnostics read and change nothing. */
(function () {
  'use strict';
  if (window.__comMorpheus) return;
  window.__comMorpheus = true;
  var API = '/plugin/comMorpheus';

  function csrfFromMeta(doc) {
    var m = doc.querySelector('meta[name="_csrf"]');
    if (!m || !m.getAttribute('content')) return null;
    var h = doc.querySelector('meta[name="_csrf_header"]');
    return { header: (h && h.getAttribute('content')) || 'X-XSRF-TOKEN', token: m.getAttribute('content'), from: 'this page' };
  }

  var csrfPromise = null;
  function getCsrf() {
    if (csrfPromise) return csrfPromise;
    var here = csrfFromMeta(document);
    if (here) return (csrfPromise = Promise.resolve(here));
    var pages = ['/operations/dashboard', '/'];
    csrfPromise = pages.reduce(function (prev, url) {
      return prev.then(function (found) {
        if (found) return found;
        return fetch(url, { credentials: 'same-origin', headers: { Accept: 'text/html' } })
          .then(function (r) { return r.ok ? r.text() : ''; })
          .then(function (html) {
            var t = html ? csrfFromMeta(new DOMParser().parseFromString(html, 'text/html')) : null;
            if (t) t.from = url;
            return t;
          })
          .catch(function () { return null; });
      });
    }, Promise.resolve(null));
    return csrfPromise;
  }

  function oneLine(s, n) { return String(s).replace(/\s+/g, ' ').slice(0, n); }

  function markReady(root) {
    var s = root.querySelector('#vc-diag-script');
    if (s && !s.getAttribute('data-ready')) {
      s.setAttribute('data-ready', '1');
      s.textContent = 'The test script is loaded.';
    }
  }

  function run(root) {
    var out = root.querySelector('#vc-diag-out');
    var copy = root.querySelector('#vc-diag-copy');
    var urls = Array.prototype.map.call(root.querySelectorAll('.vc-probe-url'), function (a) { return a.getAttribute('href'); });
    getCsrf().then(function (csrf) {
      var lines = ['csrf: ' + (csrf ? csrf.header + ' from ' + csrf.from : 'none found'), 'page: ' + location.pathname];
      var jobs = [];
      urls.forEach(function (u) {
        ['GET', 'POST'].forEach(function (m) {
          var h = { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
          if (m === 'POST' && csrf) h[csrf.header] = csrf.token;
          jobs.push(fetch(u, { method: m, credentials: 'same-origin', headers: h })
            .then(function (r) { return r.text().then(function (b) { return [m, u, r.status, b]; }); })
            .catch(function (e) { return [m, u, 'error', String(e)]; }));
        });
      });
      return Promise.all(jobs).then(function (rows) {
        rows.forEach(function (r) {
          var tr = document.createElement('tr');
          [r[0], r[1], r[2], oneLine(r[3], 160)].forEach(function (v) {
            var td = document.createElement('td'); td.textContent = v; tr.appendChild(td);
          });
          out.appendChild(tr);
          lines.push(r[0] + ' ' + r[1] + ' -> ' + r[2] + ' ' + oneLine(r[3], 200));
        });
        copy.value = lines.join('\n');
      });
    });
  }

  // ---- actions (phase 2) ----------------------------------------------------------------------

  function post(path, params) {
    return getCsrf().then(function (csrf) {
      var h = { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/x-www-form-urlencoded' };
      if (csrf) h[csrf.header] = csrf.token;
      return fetch(API + path, { method: 'POST', credentials: 'same-origin', headers: h, body: new URLSearchParams(params).toString() })
        .then(function (r) {
          return r.text().then(function (b) {
            var j = null; try { j = JSON.parse(b); } catch (e) { /* not JSON */ }
            return { status: r.status, body: j || { ok: false, error: 'HTTP ' + r.status } };
          });
        });
    });
  }

  function setStatus(bar, text, level) {
    var s = bar.querySelector('.vc-act-status');
    if (!s) return;
    s.textContent = text;
    s.className = 'vc-act-status' + (level ? ' vc-' + level : '');
  }

  function busy(bar, on) {
    Array.prototype.forEach.call(bar.querySelectorAll('.vc-act'), function (b) { b.disabled = on; });
  }

  var POLL_MS = 4000, POLL_MAX = 150; // up to 10 minutes

  function poll(bar, serverId, jobId, label, n) {
    post('/job', { serverId: serverId, jobId: jobId }).then(function (r) {
      var j = r.body;
      if (!j.ok) { busy(bar, false); setStatus(bar, label + ': ' + (j.error || 'status unavailable'), 'bad'); return; }
      if (j.done) {
        busy(bar, false);
        var good = j.resultCode === 'SUCCESS';
        setStatus(bar, label + ': ' + (good ? 'done' : (j.resultCode || j.state)) + (j.status ? ' (' + j.status + ')' : '') +
          (good ? '. Reopen the tab to see fresh data.' : ''), good ? 'ok' : 'bad');
        return;
      }
      setStatus(bar, label + ': ' + (j.state || 'running') + '…', 'warn');
      if (n >= POLL_MAX) { busy(bar, false); setStatus(bar, label + ': still running in COM. Check the job there.', 'warn'); return; }
      setTimeout(function () { poll(bar, serverId, jobId, label, n + 1); }, POLL_MS);
    }).catch(function (e) { busy(bar, false); setStatus(bar, label + ': ' + e, 'bad'); });
  }

  function act(btn) {
    var bar = btn.closest('.vc-actions');
    if (!bar) return;
    var msg = btn.getAttribute('data-confirm');
    if (msg && !window.confirm(msg)) return;
    var label = btn.textContent.trim();
    var serverId = bar.getAttribute('data-server-id');
    busy(bar, true);
    setStatus(bar, label + ': starting…', 'warn');
    post('/action', { serverId: serverId, action: btn.getAttribute('data-action') }).then(function (r) {
      var j = r.body;
      if (!j.ok || !j.jobId) { busy(bar, false); setStatus(bar, label + ': ' + (j.error || 'not started'), 'bad'); return; }
      setStatus(bar, label + ': ' + (j.state || 'PENDING') + '…', 'warn');
      setTimeout(function () { poll(bar, serverId, j.jobId, label, 1); }, POLL_MS);
    }).catch(function (e) { busy(bar, false); setStatus(bar, label + ': ' + e, 'bad'); });
  }

  function initActions(bar) {
    if (bar.getAttribute('data-init')) return;
    bar.setAttribute('data-init', '1');
    post('/caps', {}).then(function (r) {
      if (r.status === 200 && r.body && r.body.canAct) bar.hidden = false;
    }).catch(function () { /* buttons stay hidden */ });
  }

  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var run1 = t.closest('#vc-diag-run');
    if (run1) { var root = run1.closest('#vc-diag'); if (root) run(root); return; }
    var btn = t.closest('.vc-act');
    if (btn && !btn.disabled) act(btn);
  });

  function scan() {
    var root = document.getElementById('vc-diag');
    if (root) markReady(root);
    Array.prototype.forEach.call(document.querySelectorAll('.vc-actions[data-server-id]'), initActions);
  }
  scan();
  new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
})();
