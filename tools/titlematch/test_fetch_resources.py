import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fetch_resources import download, target_path, verified


class ResourceIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cache = Path(self.temp.name)
        self.item = {"path": "models/weights.bin", "url": "https://example.org/weights",
                     "bytes": 4, "sha256": hashlib.sha256(b"good").hexdigest()}

    def test_rejects_escape_and_windows_paths(self):
        for name in ("../escape", "/absolute", "C:/outside", "x\\y", ".", "x/../y"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                target_path(self.cache, name)

    def test_same_size_tampering_is_rejected(self):
        file = self.cache / "blob"
        file.write_bytes(b"evil")
        self.assertFalse(verified(self.item, file))
        file.write_bytes(b"good")
        self.assertTrue(verified(self.item, file))

    def test_verified_cache_never_uses_network(self):
        target = target_path(self.cache, self.item["path"])
        target.parent.mkdir()
        target.write_bytes(b"good")
        with patch("fetch_resources.urlopen", side_effect=AssertionError("network")):
            self.assertEqual("verified_cached", download(self.item, self.cache)["status"])

    def test_failed_transfer_preserves_existing_file_and_removes_partial(self):
        target = target_path(self.cache, self.item["path"])
        target.parent.mkdir()
        target.write_bytes(b"old!")
        with patch("fetch_resources.urlopen", side_effect=OSError("interrupted")), \
                patch("fetch_resources.time.sleep"), self.assertRaises(OSError):
            download(self.item, self.cache)
        self.assertEqual(b"old!", target.read_bytes())
        self.assertFalse(target.with_name(target.name + ".part").exists())

    def test_wrong_hash_and_oversize_never_replace_existing_file(self):
        target = target_path(self.cache, self.item["path"])
        target.parent.mkdir()
        for payload in (b"evil", b"good plus extra bytes"):
            with self.subTest(payload=payload):
                target.write_bytes(b"old!")
                with patch("fetch_resources.urlopen", side_effect=lambda *a, **k: io.BytesIO(payload)), \
                        patch("fetch_resources.time.sleep"), self.assertRaises(ValueError):
                    download(self.item, self.cache)
                self.assertEqual(b"old!", target.read_bytes())
                self.assertFalse(target.with_name(target.name + ".part").exists())

    def test_complete_verified_transfer_is_installed(self):
        with patch("fetch_resources.urlopen", return_value=io.BytesIO(b"good")):
            self.assertEqual("downloaded_verified", download(self.item, self.cache)["status"])
        self.assertTrue(verified(self.item, target_path(self.cache, self.item["path"])))


if __name__ == "__main__":
    unittest.main()
