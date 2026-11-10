import base64
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from stage_android_signing import EXPOSED_CERTIFICATE, certificate_fingerprint, decode_keystore


class PrivateSignerTest(unittest.TestCase):
    def test_missing_malformed_or_oversized_secret_is_rejected(self):
        for value in ("", "not base64!", "a" * 2_000_001):
            with self.assertRaises(ValueError):
                decode_keystore(value)

    def test_encoded_keystore_is_preserved(self):
        self.assertEqual(b"keystore bytes", decode_keystore(base64.b64encode(b"keystore bytes").decode()))

    def test_exposed_signer_and_unverifiable_output_are_rejected(self):
        for output in ("", "SHA256: short", "SHA256: " + EXPOSED_CERTIFICATE):
            with self.assertRaises(ValueError):
                certificate_fingerprint(output)

    def test_new_certificate_is_normalized(self):
        fingerprint = "12" * 32
        colon_format = ":".join(fingerprint[index:index+2] for index in range(0, 64, 2))
        self.assertEqual(fingerprint, certificate_fingerprint("SHA256: " + colon_format))


@unittest.skipUnless(shutil.which("java") and shutil.which("keytool"), "JDK 21 must be on PATH")
class PrivateKeyStagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="parchment-signing-tests-")
        cls.root = Path(cls.directory.name).resolve()
        if cls.root.parent != Path(tempfile.gettempdir()).resolve():
            raise RuntimeError("Unexpected signing fixture directory")
        cls.addClassCleanup(cls.directory.cleanup)
        cls.store_password = "fixture-store-password"
        cls.distinct_password = "  distinct-key-password  "
        cls.fixtures = {}
        for kind, password in (("PKCS12", cls.store_password), ("JKS", cls.distinct_password)):
            target = cls.root / (kind.lower() + ".keystore")
            environment = dict(os.environ, FIXTURE_STORE_PASSWORD=cls.store_password, FIXTURE_KEY_PASSWORD=password)
            result = subprocess.run([
                "keytool", "-genkeypair", "-storetype", kind, "-keystore", str(target),
                "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=Parchment Signing Fixture", "-storepass:env", "FIXTURE_STORE_PASSWORD",
                "-keypass:env", "FIXTURE_KEY_PASSWORD",
            ], env=environment, capture_output=True, text=True, timeout=30)
            if result.returncode != 0:
                raise RuntimeError("Could not generate disposable signing fixture: " + result.stderr)
            cls.fixtures[kind] = target.read_bytes()

    def check_staging(self, key_password, kind="PKCS12", success=True):
        with tempfile.TemporaryDirectory(dir=self.root) as case:
            case_root = Path(case).resolve()
            case_root.relative_to(self.root)
            runner = case_root / "runner"
            runner.mkdir()
            environment = dict(os.environ,
                ANDROID_KEYSTORE_BASE64=base64.b64encode(self.fixtures[kind]).decode("ascii"),
                ANDROID_KEYSTORE_PASSWORD=self.store_password, ANDROID_KEY_ALIAS="fixture",
                RUNNER_TEMP=str(runner), GITHUB_ENV=str(case_root / "github-env"))
            environment.pop("ANDROID_KEY_PASSWORD", None)
            if key_password is not None:
                environment["ANDROID_KEY_PASSWORD"] = key_password
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("stage_android_signing.py"))],
                env=environment, capture_output=True, text=True, timeout=30)
            github_env = case_root / "github-env"
            if success:
                self.assertEqual(0, result.returncode, result.stderr)
                exported = github_env.read_text(encoding="utf-8").splitlines()
                self.assertEqual(1, len(exported))
                name, path = exported[0].split("=", 1)
                self.assertEqual("ANDROID_KEYSTORE_PATH", name)
                staged = Path(path).resolve()
                staged.relative_to(runner)
                self.assertEqual(self.fixtures[kind], staged.read_bytes())
                self.assertIn("Private release signer verified", result.stdout)
            else:
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Private release key could not be decrypted", result.stderr)
                self.assertFalse(github_env.exists(), "Failed validation must not export a signer")
                self.assertEqual([], list(runner.iterdir()), "Failed validation must remove the staged key")

    def test_unset_key_password_uses_store_password(self):
        self.check_staging(None)

    def test_blank_key_password_uses_store_password(self):
        for password in ("", " \t\r\n", "\u00a0"):
            with self.subTest(password=repr(password)):
                self.check_staging(password)

    def test_explicit_matching_key_password(self):
        self.check_staging(self.store_password)

    def test_explicit_distinct_key_password_preserves_spaces(self):
        self.check_staging(self.distinct_password, kind="JKS")

    def test_wrong_key_password_is_rejected_and_staged_key_removed(self):
        self.check_staging("wrong-key-password", success=False)


if __name__ == "__main__":
    unittest.main()
