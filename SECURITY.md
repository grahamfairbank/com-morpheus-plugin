# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Use GitHub's private vulnerability reporting instead: on the repository page, open **Security → Report a vulnerability**. Only the maintainer sees the report.

Helpful things to include:

- the plugin version and Morpheus version;
- what a user (or someone without an account) can do that they shouldn't, and which Morpheus permissions they need;
- the steps to show it.

Use example values in anything you send: `host-a`, `192.0.2.21`, `morpheus.example.com` and so on. Never send a real API client secret, token or serial number. [CONTRIBUTING.md](CONTRIBUTING.md) explains how to replace them.

This is a community project maintained in spare time. I'll acknowledge a report within a few days, and fix confirmed issues in a patch release, credited in the changelog unless you'd rather not be named.

## Supported versions

Fixes go into the latest release only. Upgrade to the newest [release](https://github.com/grahamfairbank/com-morpheus-plugin/releases) to get them.

| Version | Supported |
|---|---|
| 0.4.x (latest) | Yes |
| 0.3.x and earlier | No. 0.2.0 and 0.3.0 have known issues when **Enable actions** is on; see the [changelog](CHANGELOG.md). |

## How the plugin is secured

The [Security section of the README](README.md#security) covers this in full. In short:

- **One shared identity.** The plugin uses one GreenLake API client for the whole appliance. Everything it does in COM runs as that client's GreenLake account, whichever Morpheus user clicked. Give the client its own account and the smallest COM role that works.
- **Credentials stay on the appliance.** Only the Morpheus appliance talks to GreenLake. The client secret is never sent to browsers, and it's sent only to HPE GreenLake addresses.
- **Read-only by default.** Action buttons are off until an administrator turns them on, and then they appear only for roles with **com-morpheus: Run HPE COM actions** at full access.
- **Every action is re-checked on the appliance:** the request method, origin and a per-host token; the user's permission and tenant; and which COM server the host is, worked out again from Morpheus data rather than taken from the browser. Actions are rate-limited per server and per user.
- **No commands on hosts and no power actions.** Actions are COM jobs only, and each one is logged with the Morpheus user who started it.

## Reviews

The plugin has had three independent security reviews: before 0.3.1, before 0.4.0, and a full review on October 31, 2026. What each one found, and how it was fixed, is in the README under [Independent security review](README.md#independent-security-review).

## Out of scope

- Problems in Morpheus, HPE GreenLake or Compute Ops Management themselves. Report those to HPE.
- Findings that need someone who can already edit the plugin settings, or who has the actions permission, to do what that role allows by design. For example, a user with the actions permission running an action on a host they own.
- What the shared GreenLake identity can do in COM. Limit it with that account's COM role.
