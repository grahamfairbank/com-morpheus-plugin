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

This builds and tests one jar into `dist/`. It's compiled against Plugin API 1.4.1 (Morpheus 9.0), and the whole test suite is repeated against 1.5.0 (Morpheus 9.1). Every change must keep both test runs green, and must not use anything that's missing from 1.4.1.

Maintainers: before a release, run `tools/check-plugin-core.sh`. It reports any change in HPE's published morpheus-plugin-core or morpheus-docs that could affect compatibility, such as a new Plugin API version or changes to classes this plugin uses.

Useful opt-in checks:
- `PREVIEW_OUT=<dir>` renders the tab to HTML from the test data (good for screenshots of UI changes).
- `COM_MOCK=1` runs the real HTTP client against HPE's public COM mock server.

## Guidelines

- **Keep it safe by default.**
  - New actions must be off unless enabled, gated by a permission, re-checked on the server, and limited to confirmed matches.
  - Actions that reconfigure servers or change power need a design discussion in an issue first. See [Why these actions](README.md#why-these-actions).
- **The plugin never runs commands on hosts.**
- **Tests:** add or update Spock specs for behavior changes. The fixtures in `src/test/resources/fixtures` are anonymized, so keep them that way.
- **No real data in issues, PRs, fixtures or screenshots.** Replace serial numbers, UUIDs, IPs, MACs, host and tenant names, API client IDs and tokens with the example values the docs use (`host-a`, `XYZ0000001`, `192.0.2.x`, `example.com`; full list in [docs/images/SCREENSHOT-SPEC.md](docs/images/SCREENSHOT-SPEC.md)). In screenshots, paint the example value over the real one. Never blur.
- **Licenses:**
  - Don't copy code or text from sources whose license doesn't allow it.
  - The Morpheus plugin API is compile-only and must never be bundled into the jar.
  - Cite new sources in [NOTICE.md](NOTICE.md).
- **Style:** follow the existing code. Keep comments short and explain *why*.
- Update [CHANGELOG.md](CHANGELOG.md) and, for user-visible changes, the README or [docs/INSTALL.md](docs/INSTALL.md).

## License of contributions

By contributing, you agree that your contribution is licensed under the project's [MIT license](LICENSE).
