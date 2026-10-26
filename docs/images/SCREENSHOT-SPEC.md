# Screenshot specification

The tab screenshots in this folder (`tab-*.png` and `cluster-summary.png`) are **rendered from anonymized test data**. They contain no customer or corporate information, and they're regenerated with `PREVIEW_OUT`. Only the shots below need a real Morpheus instance. They come from a corporate system, so they must be redacted strictly before they're added.

## Shots wanted

| File | Where | Show | Purpose |
|---|---|---|---|
| `morpheus-plugin-list.png` | Administration → Integrations → Plugins | The com-morpheus row only (name, description, version, enabled) | Install guide, step 2 |
| `morpheus-plugin-settings.png` | The plugin's **Edit** dialog | The whole settings form, with test values (see below) | Install guide, step 3 |
| `morpheus-role-permissions.png` | Administration → Roles → a test role → Permissions, filtered to "com-morpheus" | The two com-morpheus permissions and their levels | Install guide, step 4 |
| `morpheus-host-tab.png` | Infrastructure → Hosts → a physical HPE host → **HPE COM** tab | The host header and the tab strip with HPE COM selected, plus the top of the tab | README: shows the tab inside Morpheus |
| `morpheus-action-running.png` (optional) | Same tab, just after **UID light on** | The action bar with its status text ("UID light on: RUNNING…" or "…done") | Install guide, step 5 |

## Before you capture

- Use **placeholder values** in the settings dialog wherever possible, rather than redacting real ones. For example: client ID `00000000-0000-0000-0000-000000000000`, workspace ID blank, proxy blank. The secret field shows dots, but blank it or use a dummy anyway.
- Use the **light theme**, browser zoom **100%**, and a window about **1280 px** wide.
- **Crop to the content area.** Leave out the browser tabs, address bar, bookmarks, the OS taskbar and Morpheus's top bar: it shows your **name, tenant and search history**.
- Save as **PNG**. PNG has no camera metadata, but don't add any.

## Redact all of these (solid boxes only)

Cover with an **opaque solid rectangle** (black or gray). **Do not** blur, pixelate or use semi-transparent highlights: those can be reversed or read.

| Category | Examples on these screens |
|---|---|
| People | Your name, user names, email addresses, the avatar or initials in the top bar, "owner" fields |
| Organization | Tenant, cloud, group, cluster and site names; GreenLake workspace name or ID; company or domain names |
| Hosts | Host names and FQDNs, IP addresses, the appliance URL, iLO names and addresses, the iSCSI IQN |
| Hardware identity | Serial numbers, UUIDs, COM ids (they contain the serial), MAC addresses, asset tags, product IDs, if you prefer |
| Accounts and keys | API client ID, workspace ID, tokens, subscription keys, license keys |
| Content | Alert texts or messages that name hosts or people |
| Version banner | Optional: the appliance version and build in the footer, if your policy treats them as sensitive |

When unsure, redact it. A screenshot with more boxes is better than one that leaks.

## Check before sending

1. Zoom to 200% and look at every box: no characters should show around the edges.
2. Search the image for anything readable outside the content you meant to show.
3. Send the files themselves (not a document with the images pasted in), so they can be checked once more before they're committed.
