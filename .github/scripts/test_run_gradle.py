import unittest

from run_gradle import retryable_download_failure, run_with_download_retry


class GradleDownloadRetryTest(unittest.TestCase):
    def test_only_transient_download_errors_retry(self):
        self.assertTrue(retryable_download_failure("Could not GET compiler.jar. Server returned HTTP response code: 504"))
        self.assertTrue(retryable_download_failure("Could not GET compiler.jar. Received status code 504 from server"))
        self.assertTrue(retryable_download_failure("Downloading https://services.gradle.org/gradle.zip\nRead timed out"))
        self.assertFalse(retryable_download_failure("Compilation error\nCould not GET compiler.jar. HTTP 504"))
        self.assertFalse(retryable_download_failure("There were failing tests\nSocketTimeoutException\nCould not GET test URL"))
        self.assertFalse(retryable_download_failure("Could not download Gradle distribution: checksum mismatch\nHTTP 504"))
        self.assertFalse(retryable_download_failure("Illegal version for Exe: 2 is not a valid version"))

    def test_success_reuses_the_exact_command_after_download_failure(self):
        replies = iter([(1, "Could not GET compiler.jar. HTTP 504"), (0, "BUILD SUCCESSFUL")])
        calls = []
        delays = []
        def execute(command):
            calls.append(command)
            return next(replies)
        command = ["gradle", "desktopTest", "createDistributable"]
        self.assertEqual(0, run_with_download_retry(command, wait=delays.append, execute=execute))
        self.assertEqual([command, command], calls)
        self.assertEqual([10], delays)

    def test_source_failure_stops_immediately(self):
        calls = []
        def execute(command):
            calls.append(command)
            return 1, "Compilation error"
        self.assertEqual(1, run_with_download_retry(["gradle"], wait=lambda _: self.fail("must not wait"), execute=execute))
        self.assertEqual(1, len(calls))

    def test_download_retries_are_bounded(self):
        calls = []
        def execute(command):
            calls.append(command)
            return 1, "Could not GET compiler.jar. HTTP 504"
        self.assertEqual(1, run_with_download_retry(["gradle"], attempts=3, wait=lambda _: None, execute=execute))
        self.assertEqual(3, len(calls))


if __name__ == "__main__":
    unittest.main()
