# Velin agent notes

## Website

The download site lives in `site/`. It is a Next.js static export, made from the `android-site` skill template.

- Runtime: Node.js 24 or later. npm with `site/package-lock.json`.
- Content: `site/app/config.ts`. Colors: `site/app/globals.css`. The 14 `--md-sys-color-*` values are copied from `lightScheme` and `darkScheme` in `core/designsystem/src/main/kotlin/app/velin/core/designsystem/theme/Color.kt`. Copy them again when that file changes.
- Logo: `npm run build` and `npm run dev` copy `assets/logo.svg` to `site/public/logo.svg`.
- Media: none. There is no demo video, so `demo` is `null`.
- Download link: the build reads the newest non-draft release from the GitHub API. A single APK in the release counts as the universal APK. The build fails if the release has no APK.

Commands, from `site/`:

```sh
npm ci
npm test
GITHUB_TOKEN=$(gh auth token) PAGES_BASE_PATH=/velin npm run build
```

The output is in `site/out/`. The base path on GitHub Pages is `/velin`. The URL is `https://kacigaya.github.io/velin/`.

Deploy: `.github/workflows/pages.yml` runs on a push to `main` that changes `site/**`, `assets/logo.svg`, or the workflow file. It also runs when a release is published, edited, or deleted, and on a manual run. Pages must use the "GitHub Actions" source.
