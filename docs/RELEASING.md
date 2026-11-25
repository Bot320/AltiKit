# Releasing AltiKit

Releases are built, signed and published by [the release workflow](../.github/workflows/release.yml) when a version tag is pushed. No release APK is built on a personal machine.

## The release key

All releases are signed with one key, so that updates install over earlier versions.

- Keystore: `altikit-release.jks` (PKCS12, alias `altikit`, RSA 4096, valid until 2054). The key password is the same as the store password.
- Certificate SHA-256: `1F:8E:CC:CA:0A:88:F8:EC:ED:E7:53:06:3C:12:48:F4:40:F1:B5:5B:E1:FF:17:6C:CE:70:2D:BB:78:00:39:A6`. It is also set as `EXPECTED_CERT_SHA256` in the workflow, which refuses to publish an APK signed with any other key.
- The keystore and its password are never committed. Keep a backup of both somewhere offline, such as a password manager. **If the key is lost, no future release can update existing installs.** Everyone would have to uninstall, and lose their settings and trend log, before installing a release signed with a new key.

The workflow reads two repository secrets:

| Secret | Value |
|---|---|
| `ALTIKIT_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `ALTIKIT_KEYSTORE_PASSWORD` | the keystore password |

To set them (from the folder that holds the keystore):

```sh
base64 -w0 altikit-release.jks | gh secret set ALTIKIT_KEYSTORE_BASE64 --repo Bot320/AltiKit
gh secret set ALTIKIT_KEYSTORE_PASSWORD --repo Bot320/AltiKit < keystore-password.txt
```

To sign a local build with the release key, which is rarely needed:

```sh
ALTIKIT_KEYSTORE=/path/to/altikit-release.jks ALTIKIT_KEYSTORE_PASSWORD=… ./gradlew assembleRelease
```

## Making a release

1. Raise `versionCode` and set `versionName` in `app/build.gradle.kts`, and merge to `main`.
2. Tag the merge commit with `v` + `versionName`, and push the tag:

   ```sh
   git switch main && git pull
   git tag v1.2
   git push origin v1.2
   ```

3. The workflow then:
   - checks that the tag matches `versionName`;
   - runs the unit tests and builds the release APK with the release key;
   - checks the signing certificate;
   - attests the APK's build provenance;
   - creates the GitHub Release with `AltiKit-<version>.apk`, `SHA256SUMS.txt` and generated notes.

   If it fails, fix the cause on `main`, then delete the tag and push it again:
   `git push origin :v1.2 && git tag -f v1.2 && git push origin v1.2`.

Release notes can be edited on GitHub afterwards.
