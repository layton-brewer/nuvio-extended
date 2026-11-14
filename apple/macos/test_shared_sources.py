#!/usr/bin/env python3
"""Exercise the desktop overlay with temporary upstream files; no SDKs required."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MANIFEST = HERE / "shared-sources.txt"


def shared_paths():
    return [
        line for line in MANIFEST.read_text().splitlines()
        if line and not line.startswith("#")
    ]


class SharedSourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name) / "repo with spaces"
        self.here = self.root / "apple/macos"
        self.here.mkdir(parents=True)
        for name in ("apply-overlay.sh", "shared-sources.txt"):
            shutil.copyfile(HERE / name, self.here / name)
        self.shared = self.root / "mobile/composeApp/src"
        for path in shared_paths():
            source = ROOT / "mobile/composeApp/src" / path
            self.assertTrue(source.is_file(), f"Missing shared source: {path}")
            self.assertFalse((HERE / "overlay" / path).exists(), f"Duplicate overlay: {path}")
            target = self.shared / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        self.overlay = self.here / "overlay"
        self.overlay.mkdir()
        (self.overlay / "platform-only.txt").write_text("keep platform-specific code")
        for name in ("meowio-1024.png", "meowio.icns"):
            (self.here / name).write_bytes(b"icon fixture")
        (self.root / "windows").mkdir()
        (self.root / "windows/meowio.ico").write_bytes(b"windows fixture")
        self.desktop = self.root / "desktop"
        src = self.desktop / "composeApp/src"
        (src / "desktopMain/resources/icons").mkdir(parents=True)
        self.downloader = src / "desktopMain/kotlin/com/nuvio/app/features/downloads/DownloadsPlatformDownloader.desktop.kt"
        self.downloader.parent.mkdir(parents=True)
        self.downloader.write_text('get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())\n')
        (self.desktop / "composeApp/build.gradle.kts").write_text(
            'packageName = "Nuvio"\nbundleID = "com.nuvio.media.desktop"\nTargetFormat.Msi,\n'
        )

    def apply(self):
        bash = "bash"
        if os.name == "nt":
            # Use Git Bash, not the Windows Subsystem for Linux launcher on PATH.
            git_root = pathlib.Path(shutil.which("git")).resolve().parents[1]
            bash = str(git_root / "bin/bash.exe")
        return subprocess.run(
            [bash, "apply-overlay.sh", "../../desktop"],
            cwd=self.here, capture_output=True, text=True,
        )

    def test_copies_all_shared_sources_and_preserves_platform_overlay(self):
        paths = shared_paths()
        self.assertEqual(len(paths), len(set(paths)), "Manifest contains duplicate paths")
        result = self.apply()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        target = self.desktop / "composeApp/src"
        for path in paths:
            self.assertEqual((target / path).read_bytes(), (self.shared / path).read_bytes(), path)
        self.assertEqual((target / "platform-only.txt").read_text(), "keep platform-specific code")
        self.assertIn("DownloadLocation.directory()", self.downloader.read_text())
        gradle = (self.desktop / "composeApp/build.gradle.kts").read_text()
        self.assertIn('packageName = "Meowio"', gradle)
        self.assertIn("TargetFormat.Exe", gradle)
        self.assertEqual((target / "desktopMain/resources/icons/nuvio-app-icon-transparent.ico").read_bytes(), b"windows fixture")

    def test_missing_shared_source_fails_before_overlay_is_applied(self):
        path = shared_paths()[-1]
        (self.shared / path).unlink()
        result = self.apply()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"Missing shared source: {path}", result.stderr)
        self.assertFalse((self.desktop / "composeApp/src/platform-only.txt").exists())

    def test_duplicate_overlay_source_fails_before_overlay_is_applied(self):
        path = shared_paths()[0]
        duplicate = self.overlay / path
        duplicate.parent.mkdir(parents=True)
        duplicate.write_text("divergent implementation")
        result = self.apply()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"Shared source also exists in overlay: {path}", result.stderr)
        self.assertFalse((self.desktop / "composeApp/src/platform-only.txt").exists())


if __name__ == "__main__":
    unittest.main()
