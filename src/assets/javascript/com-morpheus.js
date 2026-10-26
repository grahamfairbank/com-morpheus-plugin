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
  // 0.3.1: everything the page could influence is fixed here instead of read from the DOM, so injected
  // markup elsewhere in Morpheus can't trigger an action or a probe.
  var ACTIONS = { uidOn: true, uidOff: true, collectInventory: true, iloReset: true, comMaintenanceOn: true, comMaintenanceOff: true };
  var CONFIRM = {
    iloReset: "Reset this server's iLO? The host and its VMs keep running, but the iLO, its web UI and COM's connection to it drop for a minute or two.",
    comMaintenanceOn: "Put this server in COM maintenance mode? COM suspends automatic support case creation, ServiceNow incidents and email notifications for it until you take it out. This doesn't change Morpheus maintenance mode, the host or its VMs.",
    comMaintenanceOff: "Take this server out of COM maintenance mode? COM resumes automatic support cases, ServiceNow incidents and email notifications for it."
  };
  var PROBE_URLS = ['/plugin/comMorpheus/probe', '/comMorpheus/probe', '/plugin/comMorpheus/probeOpen', '/comMorpheus/probeOpen'];
  var ROOT = '.com-morpheus'; // the plugin's own tab container

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
    var urls = PROBE_URLS;
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

  function poll(bar, serverId, token, jobId, label, n) {
    post('/job', { serverId: serverId, jobId: jobId, token: token }).then(function (r) {
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
      setTimeout(function () { poll(bar, serverId, token, jobId, label, n + 1); }, POLL_MS);
    }).catch(function (e) { busy(bar, false); setStatus(bar, label + ': ' + e, 'bad'); });
  }

  function act(btn) {
    var bar = btn.closest('.vc-actions');
    var root = btn.closest(ROOT);
    if (!bar || !root || !root.contains(bar)) return;
    var action = btn.getAttribute('data-action');
    var serverId = bar.getAttribute('data-server-id');
    var token = bar.getAttribute('data-token') || '';
    if (!ACTIONS.hasOwnProperty(action) || !/^\d{1,18}$/.test(serverId || '')) return;
    if (CONFIRM.hasOwnProperty(action) && !window.confirm(CONFIRM[action])) return;
    var label = btn.textContent.trim();
    busy(bar, true);
    setStatus(bar, label + ': starting…', 'warn');
    post('/action', { serverId: serverId, action: action, token: token }).then(function (r) {
      var j = r.body;
      if (!j.ok || !j.jobId) { busy(bar, false); setStatus(bar, label + ': ' + (j.error || 'not started'), 'bad'); return; }
      setStatus(bar, label + ': ' + (j.state || 'PENDING') + '…', 'warn');
      setTimeout(function () { poll(bar, serverId, token, j.jobId, label, 1); }, POLL_MS);
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
    if (!t.closest(ROOT)) return; // only clicks inside the plugin's own tab
    var run1 = t.closest('#vc-diag-run');
    if (run1) { var root = run1.closest('#vc-diag'); if (root && root.closest(ROOT)) run(root); return; }
    var btn = t.closest('.vc-act');
    if (btn && !btn.disabled) act(btn);
  });

  function scan() {
    var root = document.getElementById('vc-diag');
    if (root) markReady(root);
    Array.prototype.forEach.call(document.querySelectorAll(ROOT + ' .vc-actions[data-server-id]'), initActions);
  }
  scan();
  new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
})();
