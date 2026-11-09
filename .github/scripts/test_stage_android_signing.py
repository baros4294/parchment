import base64
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


if __name__ == "__main__":
    unittest.main()
