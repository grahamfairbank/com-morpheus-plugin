# Installing com-morpheus-plugin

> Unofficial community plugin, not an HPE product and not supported by HPE. See [NOTICE.md](../NOTICE.md).

You need:
- HPE Morpheus or HPE VM Essentials **9.0.x or 9.1.x**, with admin rights.
- An HPE GreenLake workspace with Compute Ops Management, where your HVM hosts are onboarded.
- Outbound HTTPS from the VME manager to `sso.common.cloud.hpe.com` and `<region>.api.greenlake.hpe.com`, directly or through a proxy.

## 1. Create a COM API client (GreenLake)

> **The plugin acts in COM as the GreenLake user who owns this API client,** for all Morpheus users and tenants. A personal API client inherits its creator's roles. COM's log will show that user for every read and every action. See [Security](../README.md#security) in the README.

1. Sign in to HPE GreenLake as a **dedicated account** for this integration (a service or shared admin account under your organisation's rules), not your personal account. Give that account the **smallest COM role** that works:
   - **view-only** while actions are off;
   - an **operator** role (one that can run server jobs) only if you will turn on **Enable actions**.

   Limit it to the relevant servers if your workspace supports scoped access.
2. Go to **Manage Workspace → Personal API clients** (older workspaces: **Manage → API**), choose **Create**, and select the **Compute Ops Management** service and the region your servers are in.
3. The API client inherits that account's roles; there is no separate role to set on the client.
4. Copy the **client ID** and **client secret**. The secret is shown only once.
5. Optional: copy the **workspace ID** (32 characters, on the Manage Workspace page). The plugin first tries the legacy GreenLake sign-in, which worked for the reference workspace. The workspace ID is needed only if that fails.

## 2. Upload the plugin (Morpheus)

Download `com-morpheus-plugin-<version>.jar` from the [Releases](https://github.com/grahamfairbank/com-morpheus-plugin/releases) page and check it with `sha256sum -c SHA256SUMS`. **The same jar works on Morpheus / VM Essentials 9.0.x and 9.1.x.** Then go to **Administration → Integrations → Plugins → Add** and upload it.

**Upgrading:** upload the new jar over the old one; settings are kept. Upgrading from 0.2.0 (which had `-vme9.0` / `-vme9.1` jars) works the same way. **Removing:** delete the plugin in the same list. The plugin leaves nothing behind on hosts or in COM.

## 3. Settings

Open the plugin in **Administration → Integrations → Plugins** and edit its settings:

| Setting | Value |
|---|---|
| COM API region URL | `https://us-west.api.greenlake.hpe.com` (or `eu-central` / `ap-northeast`) |
| API client ID / secret | from step 1 |
| GreenLake workspace ID | optional; see step 1.5 |
| Token URL override | leave blank |
| MSP tenant ID | only for MSP workspaces |
| COM web UI URL | leave blank; set only if "Open in COM" opens the wrong region |
| HTTP proxy | `http://proxy:port` if the manager needs one to reach GreenLake (no proxy authentication) |
| Only show to roles with the com-morpheus permission | off = everyone who can see the host; on = only roles granted **com-morpheus: View HPE COM tab** |
| Enable actions | off by default. On: shows UID light on/off, Collect inventory and iLO reset buttons to roles with **com-morpheus: Run HPE COM actions** at full access (Administration → Roles) |
| Show the tab on non-HPE hosts | diagnostics only; shows what the plugin sees on every hypervisor host |
| Show plugin route diagnostics on the tab | diagnostics only (0.1.1+); tests whether the browser can reach the plugin's web routes |

## 4. Roles (permissions)

The plugin adds two permissions, under **Administration → Roles → (role) → Permissions**:

| Permission | Levels | Grants |
|---|---|---|
| **com-morpheus: View HPE COM tab** | None / Read | Seeing the tab, when "Only show to roles with the com-morpheus permission" is on. Check your admin roles: plugin permissions may need to be granted explicitly. |
| **com-morpheus: Run HPE COM actions** | None / Full | The action buttons, when **Enable actions** is on. Anyone with it acts in COM **as the API client's GreenLake account**, so grant it only to roles you'd trust with that account. Since 0.3.1, editing a host's tags can't widen what it reaches: tagged matches must agree with the host's hardware before any action runs. |

Why only these actions, and why iLO reset is a safe one, is explained under [Why these actions](../README.md#why-these-actions) in the README.

## 5. Check it

![The HPE COM tab, Summary sub-tab (rendered from anonymised test data)](images/tab-summary.png)


1. Open a physical HPE host in **Infrastructure → Hosts**. The **HPE COM** tab may be under the ▼ overflow menu.
2. The first time, the tab says it is matching hosts to COM. It reads the COM server list and each server's inventory, which takes up to a minute. Reopen the tab.
3. You should see the server's summary, with **Match: confirmed by MAC**.
4. If actions are on and your role has the actions permission, the **UID light on / off, Collect inventory, iLO reset** buttons appear above the sub-tabs, after a page reload. Try **UID light on** first. It only lights the blue locator LED, and the status beside the buttons follows the COM job until it's done.

## Troubleshooting

| The tab says | Meaning / fix |
|---|---|
| Couldn't sign in to GreenLake (HTTP 401) | Wrong client ID or secret, or a new-style workspace: set the workspace ID. |
| Couldn't reach COM | The manager can't reach GreenLake: check DNS, firewall, or set the HTTP proxy. |
| rate limited by COM | GreenLake throttled the client. Wait the time shown; the plugin caches to keep calls low. |
| No matching server | None of the host's MACs are in COM's inventory: the server isn't onboarded in this workspace, or its inventory is old. Add `com-serial=<serial>` as a tag on the host. |
| more than one COM server | Shared or moved NICs. Add `com-serial=<serial>` to the host. |
| Match: probable | Only some MACs match (a replaced NIC, or a stale COM inventory). Confirm with `com-serial=<serial>` (actions then work if at least one MAC matches that server and none match another), or run an inventory collection in COM. |
| "Actions on a tag-matched host need the tag to agree with the host's hardware" | The `com-serial` / `com-id` tag names a server whose inventory has none of this host's MACs, or another server has some of them. Collect inventory in COM, or correct or remove the tag. |
| "Request refused: cross-origin" | The browser's Origin doesn't match the appliance's host name as the plugin sees it. This happens behind a reverse proxy that rewrites `Host` without sending `X-Forwarded-Host`. Configure the proxy to pass the original `Host`, or `X-Forwarded-Host`. |
| "Request refused: missing X-Requested-With header" | The request didn't come from the plugin's own script (for example, a hand-made API call or a proxy stripping headers). Use the buttons in the tab. |
| "Request refused: reopen the HPE COM tab and try again" | The tab's action token is from before the plugin was reloaded or upgraded. Reopen the tab. |
| "An action was started on this server less than 30 seconds ago" | One action per server every 30 seconds. Wait for the running job to finish. |
| No action buttons | Check that **Enable actions** is on, that your role has **Run HPE COM actions** at **Full**, and that you have reloaded the page. |
| "the API client needs a COM role that can run server jobs (HTTP 403)" | The COM API client is read-only. Give it an operator role in GreenLake. |
| "Upload: nothing happens" / "Failed to parse plugin file" | A corrupt or incomplete download: check it with `SHA256SUMS`. The upload's exact reason is in the browser's developer tools (Network → the upload request's response) and in the Morpheus log. |

The plugin logs to the Morpheus application log (`/var/log/morpheus/morpheus-ui/current`, or **Administration → Health → Morpheus Logs**) with the prefix `com-morpheus:`. Every action records the Morpheus user, the host and the COM job id.
