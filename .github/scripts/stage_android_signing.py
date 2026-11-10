"""Stage a private Android signer from CI secrets; never log or fall back to repository keys."""

import base64
import os
from pathlib import Path
import re
import subprocess
import tempfile


EXPOSED_CERTIFICATE = "ed96a0a733e9e6d38633f04a6a182c997fe60d480d9e33e5f078faa2536d26eb"


def decode_keystore(value):
    if not value or len(value) > 2_000_000:
        raise ValueError("A bounded ANDROID_KEYSTORE_BASE64 secret is required")
    decoded = base64.b64decode(value, validate=True)
    if not decoded:
        raise ValueError("Keystore secret is empty")
    return decoded


def certificate_fingerprint(output):
    match = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", output)
    fingerprint = match.group(1).replace(":", "").lower() if match else ""
    if len(fingerprint) != 64:
        raise ValueError("Signer certificate could not be verified")
    if fingerprint == EXPOSED_CERTIFICATE:
        raise ValueError("The exposed repository signing identity cannot sign a release")
    return fingerprint


def main():
    for name in ("ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS"):
        if not os.environ.get(name):
            raise ValueError(f"Configure the {name} repository secret before a release")
    data = decode_keystore(os.environ.get("ANDROID_KEYSTORE_BASE64", ""))
    root = Path(os.environ["RUNNER_TEMP"]).resolve()
    folder = Path(tempfile.mkdtemp(prefix="parchment-signing-", dir=root))
    target = folder / "release.p12"
    try:
        target.write_bytes(data)
        if os.name != "nt":
            target.chmod(0o600)
        result = subprocess.run([
            "keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v", "-keystore", str(target),
            "-storepass:env", "ANDROID_KEYSTORE_PASSWORD", "-alias", os.environ["ANDROID_KEY_ALIAS"],
        ], capture_output=True, text=True)
        if result.returncode != 0 or "Entry type: PrivateKeyEntry" not in result.stdout:
            raise ValueError("Private release signer validation failed")
        fingerprint = certificate_fingerprint(result.stdout)
        key_environment = os.environ.copy()
        key_environment["ANDROID_KEYSTORE_PATH"] = str(target)
        key_password = key_environment.get("ANDROID_KEY_PASSWORD", "")
        if not key_password.strip():
            key_environment["ANDROID_KEY_PASSWORD"] = key_environment["ANDROID_KEYSTORE_PASSWORD"]
        result = subprocess.run([
            "java", str(Path(__file__).with_name("ValidateAndroidSigningKey.java")),
        ], env=key_environment, capture_output=True, text=True)
        if result.returncode != 0:
            raise ValueError("Private release key could not be decrypted; check the key password and alias")
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as destination:
            destination.write(f"ANDROID_KEYSTORE_PATH={target}\n")
        print(f"Private release signer verified: SHA-256 {fingerprint}")
    except BaseException:
        target.unlink(missing_ok=True)
        folder.rmdir()
        raise


if __name__ == "__main__":
    main()
