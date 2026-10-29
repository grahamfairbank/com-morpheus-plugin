# Changelog

## 0.4.2 (2026-10-29)

HPE tightened the Compute Ops Management API on October 29, 2026, and 0.4.1 broke on host tabs and in the cluster summary. The COM console itself kept working. This release adapts to the live API. The published API reference hasn't changed, so each change is noted under [Known issues](README.md#known-issues).

- **Page size capped at 500** for every COM list. Alerts asked for 1000, which COM now refuses with HTTP 400.
- **Alerts without `select`.** COM refuses `select=serverId,device/id`. The plugin reads the server id from the full alert (`serverId`, `device.id`, `server.id` or the alert's `resourceUri`).
- **Single-server read fallback.** When `GET /v1/servers/{id}` answers 5xx, the plugin tries `/v1beta2/servers/{id}`, then the id with a raw `+`. If all fail, the host tab shows the server's entry from COM's server list, with a note, instead of an error. The COM maintenance check falls back the same way.
- **Sign-in order.** With a workspace ID set, the plugin signs in through the workspace token issuer first and legacy SSO last, matching what other HPE services now expect. If COM refuses a token on a read (HTTP 401), the plugin moves on to the next sign-in URL. The workspace ID setting is now recommended.
- **Morpheus 9.0.1:** the host tab now loads the full server record by id before deciding whether to show itself, because 9.0.1 can pass a shallow server. It never relied on the account argument, which 9.0.1 leaves empty.
- **New `tools/create-com-user.py`:** creates a Morpheus role for the HPE COM tabs in one run, instead of setting hundreds of role permissions by hand. It gives read-only access to the pages the tabs live on, plus *View HPE COM tab*, and optionally *Run HPE COM actions*; then it reads the role back to check it, and can create a user with it. Python standard library only; writes no files. See [Roles](docs/INSTALL.md#4-roles-permissions) in the install guide.
- Tests for each change. Tested against Plugin API 1.4.1 (Morpheus 9.0) and 1.5.0 (Morpheus 9.1). Not yet tested against the live COM API.

## 0.4.1 (2026-10-26)

- **Fix: cluster summary showed "Couldn't load alerts: … HTTP 400" and 0 alerts.** Some COM workspaces refuse the workspace-wide alerts query (`/v1/alerts`), even though COM's API reference documents it. The cluster tab now falls back to each matched server's own alerts, the same endpoint the host tab uses (and shares its one-minute cache), and doesn't retry the workspace query for six hours. It never reads the full alert history.
- When alerts can't be read at all, the tab now says **Active alerts: unknown** and shows `--` per host, instead of a misleading 0.
- The "How it works" infographic and README describe the fallback.
- **Known issue documented:** the workspace alerts HTTP 400 and its impact (none from 0.4.1 on). See [Known issues](README.md#known-issues).
- **README rewritten** to explain what the plugin is for, with a short getting-started path and a screenshot tour, including the new **cluster summary** and **COM maintenance** screenshots. All screenshots were regenerated from anonymized test data; the host tab ones now show the maintenance button.

## 0.4.0 (2026-10-26)

- **New: cluster summary tab.** Each cluster's page gets an **HPE COM** tab with one row per hypervisor host: COM match, model, serial, health, power, active alerts, support state and COM maintenance mode, plus totals and links to each host and to its COM page. It's read-only and needs the view permission. It lists only hosts that belong to the cluster's own tenant, and users of other tenants can't open it (master-tenant users can). It appears only on clusters with at least one physical HPE host, unless "show on any host" is on. It makes three bulk COM reads for the whole cluster (server list, active alerts only, warranties), cached for a minute; viewers who arrive together share one read.
- **New: Enter / Exit COM maintenance** buttons on the host tab. They run COM's maintenance-mode jobs, which suspend COM's automatic support cases, ServiceNow incidents and email notifications for that server. They don't change the server or Morpheus maintenance mode. Both ask to confirm, and they pass the same checks as the other actions (see 0.3.1). The plugin also re-reads the server from COM first and refuses a change to the state it's already in, so a button left over on an old page can't do the opposite of what it says. While maintenance is on, the host tab shows a warning, and entering it is logged as a warning with the Morpheus user's name.
- **Independent security review before release:** the same Fable reviewer as 0.3.1 checked the new tab and actions. It found two Medium issues (the cluster tab didn't check tenants; it read the workspace's full alert history on every refresh) and two Low ones (maintenance mode needed more safeguards; the tab showed on clusters without HPE hosts). All are fixed in this release, with tests, and none affected a released version.
- **Docs:** a [How it works](README.md#how-it-works) infographic (`docs/images/how-it-works.svg`, generated by `tools/draw-how-it-works.py`), and a README section on why COM maintenance mode is a safe action.
- **US English** throughout the docs and UI text (for example "anonymized", "license", "behavior").
- Tested against Plugin API 1.4.1 (Morpheus 9.0) and 1.5.0 (Morpheus 9.1).

## 0.3.1 (2026-10-25): security release

**Upgrade if you use the action buttons** (versions 0.2.0 and 0.3.0 with **Enable actions** on). The read-only tab wasn't affected.

These fixes come from an **independent security review**. A separate AI reviewer (Anthropic's Claude Fable model), working read-only from the source and HPE's published plugin API source, reviewed the action routes, the permission model and the browser script. Every finding is fixed with tests, and the fixes were re-reviewed before release. Each issue needed a signed-in Morpheus user with the actions permission; none was reachable anonymously.

- **High: tag override could target any COM server.**
  - Before: a `com-id` / `com-serial` tag on a user's own host could aim an action at any server the shared GreenLake client could reach, beyond the user's tenant.
  - Now: tags only change which server is shown. For actions, the tagged server must share at least one of the host's MAC addresses, and no other server may have any of them. A tag that matches the hardware still confirms a "probable" match.
- **Medium: request forgery.**
  - Before: the method check failed open, and cross-site protection relied entirely on Morpheus.
  - Now: action routes require POST, `X-Requested-With: XMLHttpRequest` and a same-origin Origin/Referer, on top of Morpheus's CSRF filter.
- **Medium: script gadget.**
  - Before: markup injected anywhere in the UI could trigger an action through the page-wide script.
  - Now:
    - the script only responds inside the plugin's own tab;
    - the actions it accepts, and the iLO reset confirmation, are fixed in the script rather than read from the page;
    - the diagnostics only call the plugin's fixed URLs;
    - each tab carries a **signed action token** for its host (a per-load secret), and the action routes refuse requests without it.
- **Low:**
  - job status requires the job to name this host's server, refuses non-actionable matches, and gives one answer for missing, foreign and malformed jobs;
  - unexpected errors return a reference number, and COM response bodies and connection details go to the log only;
  - one action per server every 30 seconds, and a job COM refuses doesn't block a retry;
  - job polls are no longer cached.
- **Informational:**
  - action routes re-check that the server is a hypervisor host;
  - `/caps` is gated on the actions permission, so a role with that permission but without the view permission sees the buttons.
- Docs: the README "Security" section describes exactly what is checked, and the install guide covers the new messages.

## 0.3.0 (2026-10-25)

- **One jar for Morpheus / VM Essentials 9.0 and 9.1:** `com-morpheus-plugin-<version>.jar`, with a minimum appliance version of 9.0.0. This replaces 0.2.0's `-vme9.0` / `-vme9.1` pair. It came from a post-0.2.0 finding: the 9.1 build runs unchanged on a 9.0 appliance.
  - The jar is compiled against the oldest supported Plugin API (1.4.1), so it can't use anything newer.
  - `tools/build.sh` repeats the whole test suite against Plugin API 1.5.0.
  - The same jar loads with its route controller through the plugin manager of both API versions.
- **Release-time compatibility check:** `tools/check-plugin-core.sh` compares HPE's published sources with `compat/plugin-core-baseline.properties`:
  - the latest Plugin API on Maven Central;
  - new release tags and branches in morpheus-plugin-core;
  - commits that touch the API classes this plugin uses, the plugin loader or the renderer;
  - the release notes;
  - morpheus-docs' Plugin API statements.
- Install guide: removed the claim that Morpheus refuses jars built for a newer release; 9.0.1 accepted one.

## 0.2.0 (2026-10-25): first public release

- **Pull requests welcome:** CONTRIBUTING.md, issue templates (bug, feature), a pull-request template and private security reporting.

- **Hardware sub-tab:** form factor and chassis type, processors (max and rated speed, cores, threads), memory (total, slots used, each DIMM), network adapters (location, ports, firmware) and power supplies.
- **Storage sub-tab:** volumes per controller (state, capacity, RAID type, drives, spares) and physical drives. It uses Redfish Storage, with HPE SmartStorage as the fallback.
- **Action buttons: UID light on, UID light off, Collect inventory and iLO reset.** Each starts a COM job (`POST /compute-ops-mgmt/v1/jobs`) and shows its progress until COM reports the result. iLO reset asks for confirmation first.
- **Safety:**
  - Buttons appear only when the new **Enable actions** setting is on and the user's role has the new permission **com-morpheus: Run HPE COM actions** at full access.
  - The routes (`/plugin/comMorpheus/caps`, `/action`, `/job`) check POST, the setting, full access and that the user's tenant owns the host (or that the user is in the master tenant).
  - They re-resolve the host's COM server on every call (never trusting an id from the browser) and refuse "probable" matches.
  - Job status is only reported for jobs on that host's COM server.
  - Every job start is logged with the Morpheus user.
- After a job succeeds, cached COM data for that server is dropped, so the next tab render is fresh.
- The browser script is now `com-morpheus.js`. It loads while actions or route diagnostics are on.
- **Disclaimer and attribution:**
  - The tab footer, the plugin description and the manifest now say the plugin is unofficial and not an HPE product.
  - New [NOTICE.md](NOTICE.md) lists every source with its license and use, plus the trademark notices.
- **Docs:** README and install guide now explain that all reads and actions run in COM as the API client's GreenLake user, for every Morpheus user and tenant. They give the resulting advice: a dedicated account, the smallest role, and a carefully granted actions permission. The settings help says the same.
- **Docs:** README rewritten (download, security, screenshots rendered from anonymized data); install guide gains roles, upgrading, removal and action troubleshooting; screenshot spec added.
- **Requires** a COM API client role that can run server jobs (COM answers 403 otherwise, and the tab says so).

## Earlier development builds (not released)

### 0.1.3

- **Installs on Morpheus 9.0 with web routes.** The plugin now brings its own renderer, as other community plugins with web routes do. The tab already used its own, so nothing visible changes.
- **Route diagnostics script, reworked** using ideas from github.com/builtbyfood/gfs2-rescue (ideas only; that repo has no license):
  - The script is now a plugin asset loaded on every page by a global UI component, only while route diagnostics is on.
  - POSTs carry Morpheus's CSRF token.
  - `getRoutes()` is `@CompileStatic`.

### 0.1.2

- The route controller is registered only with `setControllers`.

### 0.1.1: route probe

Phase 2 starts by proving that the browser can reach a plugin web route on the target Morpheus, before any action button depends on one.

- **New "Show plugin route diagnostics on the tab" setting** (off by default). It adds a panel with links and a **Test routes** button that calls two harmless probe routes with GET and POST, with and without the `/plugin` prefix:
  - `/comMorpheus/probe` needs the `com-morpheus: View` permission;
  - `/comMorpheus/probeOpen` needs none.

  The panel shows the HTTP status of each call and a summary to copy. The probes read and change nothing.
- **The controller is registered with `setControllers`.** VME's dispatcher looks routes up there, and `registerProvider` alone doesn't add them.
- **Clearer help text for the client secret setting:** it comes from the same Personal API client as the client ID.
- **The panel's script uses VME's CSP nonce** (`morpheus.getWebRequest().getNonceToken()`).

### 0.1.0

The first build, read-only. Verified on 2026-10-23 on a VME 9.0.2 manager with a physical ProLiant DL360 Gen10 host (vme9.0 jar).

- **HPE COM tab** on physical HPE hypervisor hosts, with Summary, Health (including iLO security), Firmware, Support and Alerts.
- **Automatic matching** of VME hosts to COM servers by network adapter MAC addresses, with no host access. `com-serial` / `com-id` tags override the match.
- **Links:** Open in COM (`/serverDetail/<id>`) and Open iLO.
- **COM client:** signs in with OAuth2 client credentials and auto-detects the token URL; caches responses; retries once on 401; reports 429 rate limits; supports an optional proxy and MSP `Tenant-Acid`.
- **Two jars from one source:** `-vme9.0` (Plugin API 1.4.1, VME 9.0.x) and `-vme9.1` (Plugin API 1.5.0, VME 9.1.x). Both are tested.
- **Handles COM API quirks** found in the spike: the warranty `deviceId` filter is ignored, `IloSecurityParams` has a capital I, `firmwareBundleId` is null, and `manufacturingDate` is undocumented.
