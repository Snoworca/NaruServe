"""MkDocs hook: makes the guides' repository links work on the site (OPS-DX-005).

The guides in docs/api are written for reading on GitHub, so some links leave
the folder the site is built from. The guides are not edited for the site;
this hook rewrites such links in the Markdown before MkDocs renders it:

* a link to a file or folder of the repository outside docs/api (README.md,
  README.ko.md, examples/...) becomes a GitHub URL on the main branch;
* a link from a Korean guide to an English one (en/app.md), or from an English
  guide to a Korean one (../app.md), becomes a relative link to that page of
  the other language sub-site, since each language is a separate MkDocs build.

Links inside docs_dir, external URLs and in-page anchors are left alone, as is
anything inside fenced code blocks or inline code.

Configuration (mkdocs.yml ``extra``):
  guide_site.lang         "ko" or "en", the language this build renders
  guide_site.repo_blob    e.g. https://github.com/Snoworca/NaruServe/blob/main
  guide_site.repo_tree    e.g. https://github.com/Snoworca/NaruServe/tree/main
"""

import os
import posixpath
import re

GUIDES = "docs/api"
# Folder of each language's guides (repository-relative) and its site prefix.
LANGUAGE_DIRS = {"ko": GUIDES, "en": GUIDES + "/en"}
LANGUAGE_PREFIX = {"ko": "", "en": "en/"}

_FENCE = re.compile(r"^\s*(`{3,}|~{3,})")
_LINK = re.compile(r"(\]\()(<?)([^)\s>]+)(>?)(\s+\"[^\"]*\")?(\))")
_CODE_SPAN = re.compile(r"(`+)(.+?)\1", re.DOTALL)

_state = {}


def on_config(config):
    settings = (config.get("extra") or {}).get("guide_site") or {}
    lang = settings.get("lang")
    if lang not in LANGUAGE_DIRS:
        raise ValueError("extra.guide_site.lang must be one of %s" % sorted(LANGUAGE_DIRS))
    _state["lang"] = lang
    _state["blob"] = settings["repo_blob"].rstrip("/")
    _state["tree"] = settings["repo_tree"].rstrip("/")
    _state["repo_root"] = os.path.dirname(os.path.abspath(config.config_file_path))
    return config


def guide_url(name):
    """Directory-style URL of a guide file name, relative to its language root."""
    stem = posixpath.splitext(name)[0]
    return "" if stem in ("README", "index") else stem + "/"


def language_of(repo_path):
    """Returns the language whose guide folder directly holds repo_path, or None."""
    folder = posixpath.dirname(repo_path)
    for lang, lang_dir in LANGUAGE_DIRS.items():
        if folder == lang_dir:
            return lang
    return None


def rewrite_target(target, src_uri, page_url, lang, repo_root, blob, tree):
    """Returns the replacement for one link target, or None to keep it."""
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target) or target.startswith(("#", "/")):
        return None
    path, sep, fragment = target.partition("#")
    if not path:
        return None
    page_dir = posixpath.dirname(posixpath.join(LANGUAGE_DIRS[lang], src_uri))
    resolved = posixpath.normpath(posixpath.join(page_dir, path))
    if resolved == ".." or resolved.startswith("../"):
        return None  # outside the repository: leave it for MkDocs to report
    suffix = sep + fragment

    if resolved == GUIDES or resolved.startswith(GUIDES + "/"):
        target_lang = language_of(resolved) if resolved.endswith(".md") else None
        if target_lang is None or target_lang == lang:
            return None  # inside this build's docs_dir: MkDocs resolves it
        here = LANGUAGE_PREFIX[lang] + page_url
        there = LANGUAGE_PREFIX[target_lang] + guide_url(posixpath.basename(resolved))
        start = posixpath.dirname(here.rstrip("/") + "/_") if here else "."
        relative = posixpath.relpath(there or ".", start)
        if there == "" or there.endswith("/"):
            relative = relative.rstrip("/") + "/"
        return relative + suffix

    kind = tree if os.path.isdir(os.path.join(repo_root, resolved)) else blob
    return "%s/%s%s" % (kind, resolved, suffix)


def rewrite_markdown(markdown, src_uri, page_url, lang, repo_root, blob, tree):
    def fix_links(text):
        def replace(match):
            new = rewrite_target(match.group(3), src_uri, page_url, lang, repo_root, blob, tree)
            if new is None:
                return match.group(0)
            return "".join((match.group(1), match.group(2), new, match.group(4),
                            match.group(5) or "", match.group(6)))
        return _LINK.sub(replace, text)

    def fix_outside_code_spans(text):
        out, last = [], 0
        for span in _CODE_SPAN.finditer(text):
            out.append(fix_links(text[last:span.start()]))
            out.append(span.group(0))
            last = span.end()
        out.append(fix_links(text[last:]))
        return "".join(out)

    result, prose, fence = [], [], None
    for line in markdown.splitlines(keepends=True):
        marker = _FENCE.match(line)
        if fence is None and marker:
            result.append(fix_outside_code_spans("".join(prose)))
            prose = []
            fence = marker.group(1)
            result.append(line)
        elif fence is not None:
            result.append(line)
            if marker and marker.group(1)[0] == fence[0] and len(marker.group(1)) >= len(fence) \
                    and line.strip() == marker.group(1):
                fence = None
        else:
            prose.append(line)
    result.append(fix_outside_code_spans("".join(prose)))
    return "".join(result)


def on_page_markdown(markdown, page, config, files):
    return rewrite_markdown(markdown, page.file.src_uri, page.url, _state["lang"],
                            _state["repo_root"], _state["blob"], _state["tree"])
