"""Unit tests for hooks/guide_links.py (OPS-DX-005 AC-4).

Run from the repository root with the standard library only:
    python -m unittest discover -s tools/docs-site -p "test_*.py"
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "hooks"))

import guide_links  # noqa: E402

REPO = os.path.dirname(os.path.dirname(HERE))
BLOB = "https://github.com/Snoworca/NaruServe/blob/main"
TREE = "https://github.com/Snoworca/NaruServe/tree/main"


def rewrite(markdown, src_uri, page_url, lang):
    return guide_links.rewrite_markdown(markdown, src_uri, page_url, lang, REPO, BLOB, TREE)


class GuideLinksTest(unittest.TestCase):

    def test_repository_files_become_github_blob_and_tree_urls(self):
        text = "[설치](../../README.ko.md#설치) and [예제](../../examples/java)"
        self.assertEqual(
            rewrite(text, "README.md", "", "ko"),
            "[설치](%s/README.ko.md#설치) and [예제](%s/examples/java)" % (BLOB, TREE))

    def test_english_guide_links_resolve_from_their_own_folder(self):
        text = "[install](../../../README.md#install)"
        self.assertEqual(rewrite(text, "app.md", "app/", "en"),
                         "[install](%s/README.md#install)" % BLOB)

    def test_korean_guide_links_to_english_pages_point_at_the_english_sub_site(self):
        self.assertEqual(rewrite("[English](en/app.md)", "app.md", "app/", "ko"),
                         "[English](../en/app/)")
        self.assertEqual(rewrite("[English](en/README.md)", "README.md", "", "ko"),
                         "[English](en/)")

    def test_english_guide_links_to_korean_pages_point_at_the_korean_sub_site(self):
        self.assertEqual(rewrite("[한국어](../app.md#routes)", "app.md", "app/", "en"),
                         "[한국어](../../app/#routes)")
        self.assertEqual(rewrite("[한국어](../README.md)", "README.md", "", "en"),
                         "[한국어](../)")

    def test_links_inside_the_build_external_urls_and_anchors_are_kept(self):
        text = "[a](routing.md#x) [b](https://example.com/x.md) [c](#local) [d](mailto:a@b.c)"
        self.assertEqual(rewrite(text, "app.md", "app/", "ko"), text)
        self.assertEqual(rewrite(text, "app.md", "app/", "en"), text)

    def test_code_blocks_and_inline_code_are_not_rewritten(self):
        text = ("Use `[x](../../README.md)` literally.\n"
                "```markdown\n[x](../../README.md)\n```\n"
                "[x](../../README.md)\n")
        self.assertEqual(
            rewrite(text, "README.md", "", "ko"),
            "Use `[x](../../README.md)` literally.\n"
            "```markdown\n[x](../../README.md)\n```\n"
            "[x](%s/README.md)\n" % BLOB)

    def test_titles_and_angle_brackets_are_preserved(self):
        self.assertEqual(rewrite('[x](<../../LICENSE> "License")', "README.md", "", "ko"),
                         '[x](<%s/LICENSE> "License")' % BLOB)


if __name__ == "__main__":
    unittest.main()
