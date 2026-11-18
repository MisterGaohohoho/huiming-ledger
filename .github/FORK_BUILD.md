# Fork build and release

This fork tracks `AldenHe/huayoupu-ledger` (`master`) on its own `main` branch.
The Android application remains offline. GitHub is used only to build, test and
distribute the application; personal ledger data is not sent to GitHub.

## Signing configuration

Repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`.
Repository variable: `ANDROID_CERT_SHA256` (lowercase SHA-256 certificate digest).
The PKCS12 key alias is `huiming-ledger`. Keep the private signing backup outside
the repository and preserve it for future updates. Never upload it as an artifact.

Only trusted `main` builds in `MisterGaohohoho/youshu-ledger` receive the signing
credentials. Pull requests and feature branches produce unsigned release APKs.
The build checks the resulting signer against the configured certificate digest.

## Publishing

1. Merge tested feature changes into `main` and wait for the Android workflow.
2. Create a `v<versionName>` tag on that exact commit.
3. Pushing the tag starts publication as a prerelease. Alternatively, dispatch
   `Publish Android APK` with an existing tag and the desired prerelease setting.
4. Publication reuses the checked APK and source archive, verifies their hashes,
   commit, version, ARM64 ABI, certificate, alignment and non-debuggable state.

The published assets include the signed ARM64 APK, checksums, build metadata and
matching public source ZIP. The fork certificate differs from upstream; export an
in-app backup before switching from an upstream-signed installation.

Current artifacts use the `Youshu-Ledger-` prefix and the application label is 有数.
The package `net.ankio.ledger` and fork certificate are unchanged, so prior fork
releases can be upgraded in place without uninstalling.

The GitHub repository is `MisterGaohohoho/youshu-ledger`. After a repository rename,
update the signing/publishing repository guards, documentation links and local
`origin` URL together. The PKCS12 alias and `huiming-recognition-diagnostics`
format identifier retain their original values for compatibility; historical
release filenames and upstream attribution also retain their original names.
