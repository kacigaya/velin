# Velin agent notes

## Website

The download site lives in `site/`. It is a Next.js static export, made from the `android-site` skill template.

- Runtime: Node.js 24 or later. npm with `site/package-lock.json`.
- Content: `site/app/config.ts`. Colors: `site/app/globals.css`. The 14 `--md-sys-color-*` values are copied from `lightScheme` and `darkScheme` in `core/designsystem/src/main/kotlin/app/velin/core/designsystem/theme/Color.kt`. Copy them again when that file changes.
- Logo: `npm run build` and `npm run dev` copy `assets/logo.svg` to `site/public/logo.svg`.
- Media: `site/public/demo.mp4` and `site/public/demo-poster.webp` come from `assets/demo.mp4` (1920x1080). Make them again when the source changes:

  ```sh
  ffmpeg -i assets/demo.mp4 -vf "scale=1280:-2:flags=lanczos" -c:v libx264 -crf 24 -preset slow -pix_fmt yuv420p \
    -c:a aac -b:a 96k -movflags +faststart site/public/demo.mp4
  ffmpeg -ss 5 -i assets/demo.mp4 -frames:v 1 -vf "scale=1280:-2" -c:v libwebp -quality 80 site/public/demo-poster.webp
  ```

  The poster is the inbox frame at 5 s.
- Download link: the build reads the newest non-draft release from the GitHub API. A single APK in the release counts as the universal APK. The build fails if the release has no APK.

Commands, from `site/`:

```sh
npm ci
npm test
GITHUB_TOKEN=$(gh auth token) PAGES_BASE_PATH=/velin npm run build
```

The output is in `site/out/`. The base path on GitHub Pages is `/velin`. The URL is `https://kacigaya.github.io/velin/`.

Deploy: `.github/workflows/pages.yml` runs on a push to `main` that changes `site/**`, `assets/logo.svg`, or the workflow file. It also runs when the `Release` workflow succeeds, when a release is published, edited, or deleted, and on a manual run. The `Release` trigger is required: `release.yml` publishes with `GITHUB_TOKEN`, and GitHub does not start other workflows from events that `GITHUB_TOKEN` makes. If you rename the `Release` workflow, change `workflows: [Release]` in `pages.yml`. Pages must use the "GitHub Actions" source.

## Typography

The app font is Google Sans Flex. It is bundled unmodified in `core/designsystem/src/main/res/font/google_sans_flex.ttf`, under the SIL Open Font License 1.1. `VelinTheme` sets it for the whole Material type scale, with the roundness axis (`ROND`) at 100. Keep `licenses/GoogleSansFlex-OFL.txt` with the font. The website uses `site/app/fonts/google-sans-flex.woff2`, under the same license.

## Android checks

CI uses JDK 21, the Gradle wrapper, and Android SDK platform 37.0. From the repo root, run:

```sh
./gradlew testDebugUnitTest :core:domain:test :core:html:test --console=plain
./gradlew :app:assembleDebug --console=plain
./gradlew :feature:mail:testDebugUnitTest --tests '*MailScreenshotTest*' -Proborazzi.record=true
```

The debug APK is in `app/build/outputs/apk/debug/`. The mail screenshots are in `feature/mail/build/outputs/roborazzi/`.
