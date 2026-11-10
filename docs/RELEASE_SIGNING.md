# Private release signing

The legacy repository JKS contains an exposed private key. Removing the working copy cannot revoke its history. It must not sign new releases. Release builds now require an external private keystore and credentials; CI rejects the exposed certificate. Pull requests use a debug key and receive no release secrets.

Create the local signer once with JDK 21 set in `JAVA_HOME`:

```powershell
./scripts/create-private-signer.ps1
. ./scripts/load-private-signer.ps1
```

The PKCS12 file and a Windows-user DPAPI-protected password remain in the ignored `.parchment-local/signing/` directory. Keep protected backups of the signer and recoverable credentials outside the repository; DPAPI files require the original Windows user profile. Do not regenerate this key for later builds.

Configure GitHub repository secrets `ANDROID_KEYSTORE_BASE64` (the private PKCS12 bytes encoded as base64), `ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEY_ALIAS`. Optional `ANDROID_KEY_PASSWORD` falls back to the store password when unset or blank; a nonblank value is preserved exactly. Supply values through GitHub's encrypted secret mechanism without printing them or adding them to source. CI validates the certificate and decrypts the private key before launching Gradle. It stages the signer in the runner's temporary directory and removes it on failure or after the job.

The published v2.0.65 workflow predates this fallback fix and has all four secrets explicitly configured. Keep its key-password secret until the updated workflow is published.

The new signer changes Android update identity. Existing legacy installs need an explicit migration: export settings/chats before a clean install and restore afterward, or prepare and validate a signing lineage for supported Android versions. A lineage retains the original key on versions below its rotation minimum; it does not make the exposed legacy key safe. Do not advertise seamless OTA compatibility before the chosen migration has been verified. The user's planned clean install covers the test handset; public-release migration still needs the final release review.

References fetched 2026-11-08: [Android app signing](https://developer.android.com/studio/publish/app-signing), [apksigner rotation](https://developer.android.com/tools/apksigner), [keytool environment passwords](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html), and [Windows DPAPI](https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.protecteddata.protect).
