# NOTICE: sources, attributions, disclaimer and trademarks

## Unofficial project

com-morpheus-plugin is an **independent, unofficial community project**. It is **not** a product of Hewlett Packard Enterprise (HPE) or Morpheus Data, LLC. It is not developed, reviewed, endorsed, sponsored, certified or supported by HPE or Morpheus Data.

The software is provided "as is", without warranty of any kind (see [LICENSE](LICENSE)). Use it at your own risk. Do not contact HPE support about it.

## Sources and how they were used

This plugin was written from scratch. **No source code, documentation text or images from the sources below are copied into this project.** It interoperates with the products they describe, and they were used only as references, within their published terms:

| Source | Owner / license | How it was used |
|---|---|---|
| [HewlettPackard/morpheus-plugin-core](https://github.com/HewlettPackard/morpheus-plugin-core) (`morpheus-plugin-api` 1.4.1 and 1.5.0 from Maven Central; branch `v1.5.x`, tags `rel-1.4.1`, `rel-1.5.0`) | Morpheus Data, LLC, under the Morpheus Data Plugin Core Source License (PSL) | Used, as the PSL permits, **solely to develop and compile this plugin**. It is a compile-time (`provided`) dependency and is **not bundled in, or redistributed with,** the plugin jar. Its published documentation (e.g. `GettingStarted.adoc`, `HTTPRouting.adoc`) and published source were read to learn the plugin interfaces. |
| [HewlettPackard/morpheus-docs](https://github.com/HewlettPackard/morpheus-docs) (branch `dev-9.1`) | HPE; no license file published | Read for product facts: which Plugin API version each release uses (`release_notes/compatibility.rst`), the CSP-nonce pattern for plugin scripts (`getting_started/guides/catalog_layout.rst`), and log locations (`troubleshooting/Attaching_Logs.rst`). Cited by link and paraphrased; no text copied. |
| [HPE GreenLake Developer Portal: Compute Ops Management API](https://developer.greenlake.hpe.com/docs/greenlake/services/compute-ops-mgmt/public/) (API reference and OpenAPI document, [job definitions](https://developer.greenlake.hpe.com/docs/greenlake/services/compute-ops-mgmt/jobs), [authentication guide](https://developer.greenlake.hpe.com/docs/greenlake/guides/public/authentication/authentication), public mock server) | HPE | The public API documentation this plugin calls: endpoints, fields, job template IDs and the token flow. The public mock server is used in an opt-in test. |
| [HPE Developer Community blog: COM API access tokens](https://developer.hpe.com/blog/how-to-use-an-api-access-token-for-hpe-greenlake-for-compute-ops-management/) | HPE | Background on API client credentials. |
| [jullienl/HPE-COM-PowerShell-Library](https://github.com/jullienl/HPE-COM-PowerShell-Library) | Lionel Jullien; MIT license | Reference for the GreenLake token endpoints (legacy SSO and workspace issuer URLs). No code copied. |
| [builtbyfood/gfs2-rescue](https://github.com/builtbyfood/gfs2-rescue) and [builtbyfood/morpheus-vm-folders](https://github.com/builtbyfood/morpheus-vm-folders) | Community authors; no license published | Design ideas only: a plugin renderer of its own, sending Morpheus's CSRF token on plugin POSTs, and loading plugin scripts through a global UI component. No code copied. |

### Test data

The test fixtures in `src/test/resources/fixtures` come from the author's own lab (one COM workspace and one Morpheus host). They are **anonymized**: serial numbers, UUIDs, IP addresses, host and iLO names, MAC vendor prefixes, subscription keys and volume names are replaced with placeholders.

### What the plugin jar contains

Only this project's own compiled classes, templates, one script and one icon. It contains no third-party code. The Morpheus plugin API, Groovy and other runtime libraries are supplied by the Morpheus appliance at run time.

## Trademarks

This project refers to the following names only to describe what it works with (nominative use). No affiliation or endorsement is implied.

- **HPE** and **Hewlett Packard Enterprise**, and the HPE logo
- **HPE GreenLake** and **GreenLake**
- **HPE Compute Ops Management** (abbreviated "COM" in this project)
- **HPE Morpheus**, **Morpheus**, **HPE Morpheus VM Essentials** and **HPE VM Essentials** (abbreviated "VME" in this project)
- **HPE ProLiant** and **ProLiant**
- **HPE iLO**, **iLO** and **Integrated Lights-Out**
- **HPE Smart Array**

These are trademarks or registered trademarks of Hewlett Packard Enterprise Company, or of Morpheus Data, LLC (an HPE company), in the United States and other countries.

**Redfish** is a trademark of DMTF. Other product and company names that appear in device data shown by the plugin (for example processor, memory and drive model names) are trademarks of their respective owners.

The plugin's icon is an original drawing and does not use any HPE logo.
