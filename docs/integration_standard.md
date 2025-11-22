# xnat_dicomweb_plugin: Proxy Site Integration Standard

**Effective date:** November 22, 2025  
**Scope:** Applies to every xnat_*_plugin repository.

## Module Federation Requirement
- Build with Webpack 5 (or another bundler that supports Module Federation) and emit a `remoteEntry.js` entry point that the proxy site can reach.
- Configure `ModuleFederationPlugin` with a stable `name` (match the plugin id), `filename: "remoteEntry.js"`, at least one exposed module such as `./AppShell`, and declare shared dependencies (`react`, `react-dom`, `react-router-dom`, `@tanstack/react-query` if used) as singletons aligned with the versions used by `xnat_proxy_site`.
- Publish the remote entry under an authenticated path served by this plugin so the proxy can load it at runtime (default expectation: `https://<xnat-host>/plugins/xnat_dicomweb_plugin/remoteEntry.js`).
- Fail CI when the remote entry is missing, when the exposed module signature changes unexpectedly, or when dependency negotiation with `xnat_proxy_site` would break.

## Plugin Manifest Contract
- Serve a manifest JSON payload (via `/xapi/plugins` or the approved endpoint) that includes `id`, `name`, `version`, `category`, `icon`, and `routes` as outlined in `xnat_proxy_site/docs/plugin-ui-discovery-plan.md`.
- Every route must define `path`, `title`, and an `entry` pointing to this plugin's Module Federation remote entry; optional `category` overrides or icons may be added per route.
- Set `sandbox: false` whenever Module Federation is used; iframe fallbacks require explicit approval from the platform architecture group.
- Keep the manifest schema in sync with published updates from the proxy site repository.

## Validation Checklist
1. `npm run build` (or equivalent) outputs `dist/remoteEntry.js` for the production configuration.
2. Deploy to a development XNAT stack and verify `curl -I https://<xnat-host>/plugins/xnat_dicomweb_plugin/remoteEntry.js` returns `200`.
3. Fetch the plugin manifest and confirm each `routes[].entry` references the deployed remote entry URL.
4. Smoke-test the plugin inside the latest `xnat_proxy_site` Apps menu to ensure the Module Federation remote loads without sandboxing.

## Compliance
- No pull request may merge and no release tag may be cut until all checklist items pass and this document stays up to date.
- Exceptions must be documented here along with approval from the platform architecture group.

## Rationale
Module Federation keeps the proxy site's Apps menu dynamic while still letting plugins ship independently. Following this standard ensures every xnat_dicomweb_plugin release remains compatible with the automatic plugin discovery pipeline.
