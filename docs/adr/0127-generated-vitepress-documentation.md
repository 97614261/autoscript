# ADR 0127 Generated VitePress documentation

Date: 2026-10-19
Status: accepted

## Decision

Build the existing docs-site as a local, independently buildable documentation
site using Vue 3, VitePress 1.6.4 and the MIT-licensed @vue/theme 2.4.0. This theme
declares VitePress ^1.2.2 as its peer; use the compatible stable line rather than
an untested prerelease. Override its transitive Vite to MIT-licensed 6.4.4 to
address development-server security advisories; the Vue plugin supports Vite 6.
Validate the static build and preview with that exact lockfile. Bundle the
VueUse shared helpers in SSR so the theme's v10 and VitePress's v12 dependencies
resolve their own helpers rather than an incompatible hoisted external package.
Pin dependencies with an npm lockfile. No service, account,
remote search, analytics, deployment or Android/Rust runtime dependency is added.

The API JSON files in schema/api-schema/functions remain authoritative. A small
Node generator produces one reference page per exact API, including ordered
parameters, return/null behavior, capability, timeout, cancellation, resource
ownership, aliases and opcode. It rejects duplicate names/opcodes/routes and
cross-checks the Rust-generated contract snapshot. A searchable catalog and
sidebar use the same data; do not maintain a parallel hand-written API list.

Generated pages, local caches, output and exact copies of existing brand assets
are ignored. Generation only removes an obsolete file when the prior owned-file
manifest and generated marker both identify it; unknown files are not deleted.
Do not copy internal verification records, private scripts, device addresses or
reference/decompiled sources into the public-facing site.

Keep the existing api-codegen HTML snapshot and glyph HTML reference intact for
contract checks and compatibility. The new site presents the glyph guide in
Markdown. Website changes do not remove Android/Rust project files or change
script execution, API versions, schemas or capabilities.

## Validation

Require generator tests, exact API/snapshot coverage, production static build and
link checks. Add an independent documentation CI job; no Android SDK/NDK or Rust
toolchain is required to build the site. Browser checks cover the home page,
API search/navigation, detail tables, theme switching and narrow layouts when a
browser is available. Compilation alone is not a visual acceptance claim.
