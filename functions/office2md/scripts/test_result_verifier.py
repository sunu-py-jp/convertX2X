"""Regression checks for optional asset counts in the real-result verifier."""

from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_full_feature import DEFAULT_LIMITS, extract


class ResultVerifierTest(unittest.TestCase):
    def test_zero_counts_allow_assets_but_explicit_counts_and_bytes_still_apply(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "result.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("document.md", "# Document\n")
                output.writestr("report.json", "{}")
                for number in range(1, 4):
                    output.writestr(f"images/image-{number:04d}.png", b"fixture")
            for images, shapes in ((0, 0), (1, 0), (0, 1)):
                limits = dict(DEFAULT_LIMITS, maxImages=images, maxShapes=shapes)
                self.assertEqual(len(extract(archive, root / f"unlimited-{images}-{shapes}", limits)), 5)
            with self.assertRaisesRegex(ValueError, "entry count"):
                extract(archive, root / "limited", dict(DEFAULT_LIMITS, maxImages=1, maxShapes=1))
            with self.assertRaisesRegex(ValueError, "output limit"):
                extract(archive, root / "bytes", dict(DEFAULT_LIMITS, maxOutputBytes=1))


if __name__ == "__main__":
    unittest.main()
