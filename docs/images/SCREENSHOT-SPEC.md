# Screenshot specification

There are two kinds of screenshot in this folder:

- **Rendered:** `tab-*.png` and `cluster-summary.png`. They are rendered from the plugin's test data (`PREVIEW_OUT` in `LiveChecksSpec`). When they're captured, the test data's names, serial numbers and addresses are swapped for the example values below.
- **Real:** `install-*.png`, `morpheus-host-page.png` and `morpheus-cluster-page.png`. They come from a real Morpheus 9.0.1 appliance and GreenLake workspace, supplied by the maintainer. Every sensitive value is **painted over with an example value** before the image is committed. The browser's address bar and tabs are cropped off.

## Example values

Use these and nothing else, so a reader sees the same name for the same thing in every image and every section of the docs:

| Thing | Example value |
|---|---|
| GreenLake workspace name / ID | `my-workspace` / `4f2a9c7e1b8d4e6fa3c5d7e9b1f20a64` |
| Issuer URL | `https://global.api.greenlake.hpe.com/authorization/v2/oauth2/4f2a9c7e1b8d4e6fa3c5d7e9b1f20a64/token` |
| API client ID / secret | `7c3e8a12-5b4f-4d9e-a6c1-2f8b0d4e9a37` / `a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6` |
| Workspace address, phone, email | `1 Example Way, Example City 00000`, `555-555-0100`, `storage-admin@example.com` |
| Morpheus appliance and login | `morpheus.example.com`; username `admin`, display name **Admin User** (initials AU) |
| Cloud, cluster, site tag | `cloud-a`, `cluster-a`, `site-a` |
| Hosts | `host-a`, `host-b`, `host-c` (then `host-d`... for larger examples) |
| Serial numbers | `XYZ0000001` for host-a, `XYZ0000002` for host-b, and so on |
| host-a's iLO | `192.0.2.21`, `ilo-host-a.example.com` |
| host-a's UUID | `3a7f1c2e-9b4d-4e6a-8c1f-5d2b7e9a0c41` |
| Other IP addresses | `192.0.2.x` (the documentation range) |

The workspace, client and email values are shared with [dscc-morpheus-plugin](https://github.com/grahamfairbank/dscc-morpheus-plugin), whose install guide shows the same GreenLake screens.

## What to replace

| Category | Examples on these screens |
|---|---|
| People | Names, user names, email addresses; the initials beside the name in the top bar |
| Organization | Tenant, cloud, group, cluster and site names; GreenLake workspace name or ID; company or domain names, addresses and phone numbers |
| Hosts | Host names and FQDNs, IP addresses, the appliance URL, iLO names and addresses |
| Hardware identity | Serial numbers, UUIDs, COM ids (they contain the serial), MAC addresses |
| Accounts and keys | API client ID and secret, tokens, subscription and license keys |
| Content | Alert texts or messages that name hosts or people |

**Paint the example value in place of the real one**: clear the text with the background color of that spot and write the example in a similar size and color. Block out only things that aren't text: photos, avatars, other companies' logos. Never blur: it can sometimes be reversed.

## Shots wanted

None at the moment. New shots go in `docs/images` as `install-NN-<what>.png` (install guide) or a descriptive name (README).

## Before you capture

- Type the **example values** into forms wherever you can (client ID, workspace ID), so there's less to paint over. The secret field shows dots; use a dummy anyway.
- Use the **light theme**, browser zoom **100%**, and a window about **1920 px** wide.
- Save as **PNG**.
- Send the files themselves (not a document with the images pasted in), so they can be checked and painted before they're committed.
