# Signed application updates

The application stays local for ledger, OCR, import and reports. Only the native,
user-initiated update client connects to the configured HTTPS update origin.
The bundled WebView keeps network loads and file/content access disabled.

Repository Actions secret `UPDATE_BASE_URL` configures the public HTTPS origin at build
time (`YOUSHU_UPDATE_BASE_URL`). A local build without it uses a non-working
`.invalid` origin. No GitHub repository URL or write credential is required by
the client. The corresponding native updater public key is checked into
`app/src/main/res/raw/update_public_key.der`; public keys are not secrets.

`UPDATE_MANIFEST_PRIVATE_KEY` is a repository Actions secret, separate from the
existing APK signing key. Keep its offline backup outside the repository. Never
publish the private key or Worker deployment configuration. The Worker source
directory is locally ignored and explicitly excluded from public source exports.

After exact-commit checks and APK signature verification, the release workflow
uploads versioned APK and source assets before signing a channel envelope. Normal
version tags publish a stable release; `-rc.`, `-beta.` and `-alpha.` tags and a
manual run with `prerelease: true` do not advance the stable channel. The channel
is an independent `update-channel` branch containing only signed `stable.json`.
Never replace an already published version with different APK bytes.

The root README is local-only and excluded from Git and public source exports.
Public release titles and descriptions use English. The signed in-app update
notes are read separately from `.github/RELEASE_NOTES.md`.

After publishing stable metadata, `scripts/prune-releases.py` verifies the signed
channel and the latest release assets, then removes other published Release
entries and their attachments. Git tags and unpublished drafts are retained.
Prerelease publication does not run cleanup, so it cannot remove assets still
referenced by the stable updater. The cleanup command defaults to a dry run and
requires `--apply` to delete releases.

The RSA SHA-256 / PKCS#1 v1.5 signature covers the decoded payload bytes. The
envelope uses `schemaVersion`, `algorithm`, `keyId`, `payload` and `signature`;
the last two fields are standard Base64. The signed payload contains relative
`apkPath` and `sourcePath` under a versioned release; the client joins them to its
pinned origin, so the public channel need not contain the deployment domain. The client verifies signature, time
window, version monotonicity, package, ABI, SDK, exact same-origin download paths,
file length and SHA-256, then compares the APK signers with the installed app.
It repeats verification immediately before handing a private content URI to the
system installer. HTTP redirects, arbitrary URLs and automatic installs are not
accepted. Canceled/incomplete downloads cannot reach the installer.

The manifest expires after 30 days. A weekly workflow verifies the existing
signature and renews only its timestamps; it cannot silently promote another
release. GitHub may disable scheduled workflows on inactive public repositories,
so check the renewal workflow periodically if development stops. A stale or
unavailable manifest is shown as an error, never as proof that the app is current.

The first updater-enabled version needs one manual signed upgrade. Android still
asks the user to allow installation and confirm the install. Package ID, signing
certificate and database schema are unchanged by this feature. No uninstall or
automatic data wipe is used.
