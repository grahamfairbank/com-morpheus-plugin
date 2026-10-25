# Changelog

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
  - New [NOTICE.md](NOTICE.md) lists every source with its licence and use, plus the trademark notices.
- **Docs:** README and install guide now explain that all reads and actions run in COM as the API client's GreenLake user, for every Morpheus user and tenant. They give the resulting advice: a dedicated account, the smallest role, and a carefully granted actions permission. The settings help says the same.
- **Docs:** README rewritten (download, security, screenshots rendered from anonymised data); install guide gains roles, upgrading, removal and action troubleshooting; screenshot spec added.
- **Requires** a COM API client role that can run server jobs (COM answers 403 otherwise, and the tab says so).

## Earlier development builds (not released)

### 0.1.3

- **Installs on Morpheus 9.0 with web routes.** The plugin now brings its own renderer, as other community plugins with web routes do. The tab already used its own, so nothing visible changes.
- **Route diagnostics script, reworked** using ideas from github.com/builtbyfood/gfs2-rescue (ideas only; that repo has no licence):
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
