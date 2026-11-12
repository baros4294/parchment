import unittest

from check_sbom import MIN_LIBRARIES, problems


def lib(group, name="x"):
    return {"type": "library", "group": group, "name": name}


class CheckSbomTest(unittest.TestCase):
    def test_modules_only_sbom_fails(self):
        # The shape the v2.1.6 release shipped: the two Gradle modules and nothing else.
        sbom = {"components": [{"type": "library", "group": "Parchment", "name": "composeApp"},
                               {"type": "library", "group": "Parchment", "name": "androidApp"}]}
        self.assertTrue(problems(sbom))

    def test_full_sbom_passes(self):
        groups = ["io.ktor", "org.jetbrains.compose.runtime", "org.jetbrains.kotlinx"]
        sbom = {"components": [lib(groups[i % 3], str(i)) for i in range(MIN_LIBRARIES)]}
        self.assertEqual([], problems(sbom))

    def test_missing_shipped_group_fails(self):
        sbom = {"components": [lib("io.ktor", str(i)) for i in range(MIN_LIBRARIES)]}
        self.assertEqual(2, len(problems(sbom)))


if __name__ == "__main__":
    unittest.main()
