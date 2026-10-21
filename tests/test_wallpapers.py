"""Tests wallpaper scaling and 4K display optimization.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import unittest
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


class WallpaperScalingTest(unittest.TestCase):
    def test_launcher_uses_fit_center(self):
        """Verifies WallpaperShuffleManager uses ScaleType.FIT_CENTER so wallpapers fit the display."""
        src_path = ROOT / "launcher" / "app" / "src" / "main" / "java" / "com" / "gathin" / "tvhome" / "WallpaperShuffleManager.java"
        self.assertTrue(src_path.is_file(), "WallpaperShuffleManager.java not found")
        content = src_path.read_text(encoding="utf-8")
        self.assertIn("wallA.setScaleType(ImageView.ScaleType.FIT_CENTER);", content)
        self.assertIn("wallB.setScaleType(ImageView.ScaleType.FIT_CENTER);", content)
        self.assertNotIn("ScaleType.CENTER_CROP", content, "Should not use CENTER_CROP for wallpapers")

    def test_launcher_has_4k_display_detection(self):
        """Verifies 4K display detection exists in WallpaperShuffleManager."""
        src_path = ROOT / "launcher" / "app" / "src" / "main" / "java" / "com" / "gathin" / "tvhome" / "WallpaperShuffleManager.java"
        content = src_path.read_text(encoding="utf-8")
        self.assertIn("is4kDisplay", content)
        self.assertIn("3840", content)
        self.assertIn("2160", content)

    def test_push_wallpapers_targets_4k(self):
        """Verifies push_wallpapers.sh scales images up to 4K (3840, 2160)."""
        script_path = ROOT / "tools" / "push_wallpapers.sh"
        self.assertTrue(script_path.is_file(), "push_wallpapers.sh not found")
        content = script_path.read_text(encoding="utf-8")
        self.assertIn("(3840, 2160)", content)
        self.assertNotIn("(1920, 1920)", content)

    def test_4k_thumbnail_scaling_logic(self):
        """Tests that PIL thumbnailing to (3840, 2160) fits any aspect ratio within 4K bounds."""
        test_cases = [
            # (original_w, original_h, expected_w, expected_h)
            (5712, 4284, 2880, 2160),  # 4:3 landscape
            (4032, 3024, 2880, 2160),  # 4:3 landscape
            (3840, 2160, 3840, 2160),  # 16:9 4K native
            (7680, 4320, 3840, 2160),  # 16:9 8K downscale
            (3024, 4032, 1620, 2160),  # 3:4 portrait
            (1200, 1600, 1200, 1600),  # small portrait (remains native)
            (2062, 3664, 1216, 2160),  # tall portrait
        ]
        target_box = (3840, 2160)
        for orig_w, orig_h, exp_w, exp_h in test_cases:
            with self.subTest(orig=(orig_w, orig_h)):
                im = Image.new("RGB", (orig_w, orig_h))
                im.thumbnail(target_box, Image.Resampling.LANCZOS)
                self.assertLessEqual(im.width, target_box[0])
                self.assertLessEqual(im.height, target_box[1])
                self.assertEqual(im.size, (exp_w, exp_h))
                # Verify aspect ratio is preserved
                orig_aspect = orig_w / orig_h
                scaled_aspect = im.width / im.height
                self.assertAlmostEqual(orig_aspect, scaled_aspect, places=2)


if __name__ == "__main__":
    unittest.main()
