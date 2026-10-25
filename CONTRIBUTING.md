# Contributing to com-morpheus-plugin

**Pull requests are welcome.** This is a community project, and it gets better with outside help: bug fixes, support for more server generations or COM regions, new read-only views, documentation and translations.

> This is an unofficial project, not an HPE product (see [NOTICE.md](NOTICE.md)). Contributions are reviewed by the maintainers, not by HPE.

## Before you start

- **Bugs and ideas:** open an [issue](../../issues/new/choose) first if the change is large, so we can agree on the approach.
- **Small fixes:** open a pull request directly.
- **Security issues** (for example, a way around the permission or tenant checks): please don't open a public issue. Use GitHub's **private vulnerability reporting** (Security → Report a vulnerability) instead.

## Build and test

You only need Docker:

```bash
tools/build.sh
```

This builds and tests both jars (`-vme9.0` against Plugin API 1.4.1, `-vme9.1` against 1.5.0) into `dist/`. Every change must keep both builds green.

Useful opt-in checks:
- `PREVIEW_OUT=<dir>` renders the tab to HTML from the test data (good for screenshots of UI changes).
- `COM_MOCK=1` runs the real HTTP client against HPE's public COM mock server.

## Guidelines

- **Keep it safe by default.**
  - New actions must be off unless enabled, gated by a permission, re-checked on the server, and limited to confirmed matches.
  - Actions that reconfigure servers or change power need a design discussion in an issue first. See [Why these actions](README.md#why-these-actions).
- **The plugin never runs commands on hosts.**
- **Tests:** add or update Spock specs for behaviour changes. The fixtures in `src/test/resources/fixtures` are anonymised, so keep them that way.
- **No real data in issues, PRs, fixtures or screenshots.** Redact serial numbers, UUIDs, IPs, MACs, host and tenant names, API client IDs and tokens. Use solid boxes, not blur (see [docs/images/SCREENSHOT-SPEC.md](docs/images/SCREENSHOT-SPEC.md)).
- **Licences:**
  - Don't copy code or text from sources whose licence doesn't allow it.
  - The Morpheus plugin API is compile-only and must never be bundled into the jar.
  - Cite new sources in [NOTICE.md](NOTICE.md).
- **Style:** follow the existing code. Keep comments short and explain *why*.
- Update [CHANGELOG.md](CHANGELOG.md) and, for user-visible changes, the README or [docs/INSTALL.md](docs/INSTALL.md).

## Licence of contributions

By contributing, you agree that your contribution is licensed under the project's [MIT licence](LICENSE).
