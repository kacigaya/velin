# Releasing Velin

CI builds and tests every push to `main` and every pull request. A tag that starts with `v` builds a signed APK and publishes a GitHub release.

## One-time setup: signing key

The signing key identifies Velin to Android. Every update must use the same key. If you lose the key, users must uninstall Velin to install a new version.

1. Create the key on your own computer, not on a server:

   ```sh
   keytool -genkeypair -keystore velin-release.jks -storetype PKCS12 \
     -alias velin -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Velin"
   ```

2. Store `velin-release.jks` and its passwords in your password manager. Keep an offline backup.
3. Encode the key for GitHub:

   ```sh
   base64 -w0 velin-release.jks
   ```

4. In the repository, open **Settings → Secrets and variables → Actions**. Add these repository secrets:

   | Secret | Value |
   |---|---|
   | `VELIN_KEYSTORE_BASE64` | Output of step 3 |
   | `VELIN_KEYSTORE_PASSWORD` | Keystore password |
   | `VELIN_KEY_ALIAS` | `velin` |
   | `VELIN_KEY_PASSWORD` | Key password |

Never commit the keystore. `.gitignore` excludes `*.jks`, `*.keystore` and `*.p12`.

## Make a release

1. Set `versionName` and increase `versionCode` in `app/build.gradle.kts`.
2. Write the release notes in `docs/releases/v<version>.md`. Without this file, GitHub generates notes from the commits.
3. Commit and push. Wait for CI to pass.
4. Tag and push the tag:

   ```sh
   git tag v0.1.0-alpha
   git push origin v0.1.0-alpha
   ```

The tag must be `v<versionName>` or `v<versionName>-<suffix>`. A tag with a suffix makes a pre-release. The workflow then:

1. Checks the tag against `versionName`.
2. Builds the native Tuta SDK from the pinned release and runs all tests.
3. Builds and signs the release APK, then verifies the signature.
4. Publishes `velin-<tag>.apk` and `SHA256SUMS` to a GitHub release.

## Build time

The native SDK build takes the longest. GitHub caches the NDK and the Rust build, so later runs are faster.
