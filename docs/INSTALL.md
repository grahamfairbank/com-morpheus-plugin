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

Pick the jar for your manager's version (**Administration → Health** shows it):

- VME 9.0.x: `com-morpheus-plugin-<version>-vme9.0.jar`
- VME 9.1.x: `com-morpheus-plugin-<version>-vme9.1.jar`

Then go to **Administration → Integrations → Plugins → Add** and upload it. Morpheus refuses a jar whose minimum version is higher than the appliance's. Don't install the 9.0 jar on a 9.1 appliance: it is built against the older plugin API.

**Upgrading:** upload the new jar of the same line over the old one; settings are kept. **Removing:** delete the plugin in the same list. The plugin leaves nothing behind on hosts or in COM.

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
| **com-morpheus: View HPE COM tab** | None / Read | Seeing the tab, when "Only show to roles with the com-morpheus permission" is on. Admin roles usually have it already. |
| **com-morpheus: Run HPE COM actions** | None / Full | The action buttons, when **Enable actions** is on. Anyone with it acts in COM **as the API client's GreenLake account**, so grant it only to roles you'd trust with that account. |

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
| Match: probable | Only some MACs match (a replaced NIC, or a stale COM inventory). Confirm with `com-serial=<serial>`, or run an inventory collection in COM. Actions stay disabled until it's confirmed. |
| No action buttons | Check that **Enable actions** is on, that your role has **Run HPE COM actions** at **Full**, and that you have reloaded the page. |
| "the API client needs a COM role that can run server jobs (HTTP 403)" | The COM API client is read-only. Give it an operator role in GreenLake. |
| "Upload: nothing happens" / "Failed to parse plugin file" | The wrong jar for the appliance line, or a corrupt download. Check `SHA256SUMS`, and use `-vme9.0` for 9.0.x and `-vme9.1` for 9.1.x. |

The plugin logs to the Morpheus application log (`/var/log/morpheus/morpheus-ui/current`, or **Administration → Health → Morpheus Logs**) with the prefix `com-morpheus:`. Every action records the Morpheus user, the host and the COM job id.
