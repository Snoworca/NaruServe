#!/usr/bin/env python3
"""Checks the built GitHub Pages site and its workflow (OPS-DX-005).

Run from the repository root after ``mkdocs build --strict``:

    python tools/docs-site/check_site.py [--site build/docs-site]

It exits 1 and lists every failure when:

* the site holds an HTML page that is not one of the guides in docs/api
  (Korean, at the root) or docs/api/en (English, under en/), or misses one;
* a page's language selector does not point at the same page in the other
  language, or a page's navigation misses a guide of its language or the
  javadoc.io API reference;
* a language's search index is missing or holds pages of the other language;
* a built page holds a relative link that leaves the site, ends in ``.md``
  or points at nothing in the site;
* the workflow does not pin every action to a commit SHA, runs Gradle, or
  deploys from anything but a push to main or a manual dispatch;
* a Python package in the docs requirements is not pinned with ``==``.

Only the standard library is needed for the site checks; the workflow check
uses PyYAML, which the pinned docs toolchain installs.
"""

import argparse
import json
import os
import posixpath
import re
import sys
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS_DIR = os.path.join(REPO_ROOT, "docs", "api")
WORKFLOW = os.path.join(REPO_ROOT, ".github", "workflows", "docs-pages.yml")
REQUIREMENTS = os.path.join(REPO_ROOT, "tools", "docs-site", "requirements.txt")
JAVADOC_URL = "https://javadoc.io/doc/com.snoworca.naruserve/naruserve-api"
SITE_URL = "https://snoworca.github.io/NaruServe/"
REDIRECT_SCRIPT = '<script id="naruserve-lang-redirect">'
MEMORY_SCRIPT = '<script id="naruserve-lang-memory">'
LANGUAGES = {"ko": "", "en": "en/"}
# Pages the theme adds on its own, not guides.
THEME_PAGES = {"404.html", "en/404.html"}
SHA_PIN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")


def guide_url(name):
    """Site URL (directory form, relative to a language root) of a guide file."""
    stem = name[:-3]
    return "" if stem in ("README", "index") else stem + "/"


def expected_pages():
    """Maps each expected page URL (relative to the site root) to its language."""
    pages = {}
    for lang, prefix in LANGUAGES.items():
        folder = os.path.join(DOCS_DIR, prefix)
        for name in sorted(os.listdir(folder)):
            if name.endswith(".md") and os.path.isfile(os.path.join(folder, name)):
                pages[prefix + guide_url(name)] = lang
    return pages


def page_file(url):
    return url + "index.html"


def file_url(rel_path):
    """Inverse of page_file for a built HTML file path relative to the site."""
    if rel_path.endswith("index.html"):
        return rel_path[: -len("index.html")]
    return rel_path


class LinkParser(HTMLParser):
    """Collects every href, the language selector and the navigation links."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.hrefs = []
        self.alternates = {}
        self.head_alternates = {}
        self.nav_hrefs = []
        self._nav_depth = 0
        self._stack = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = (attrs.get("class") or "").split()
        if tag not in ("br", "img", "input", "link", "meta", "hr", "source", "path"):
            in_nav = tag == "nav" and "md-nav--primary" in classes
            self._stack.append(in_nav)
            if in_nav:
                self._nav_depth += 1
        href = attrs.get("href")
        if href is None:
            return
        if tag == "a" and "md-select__link" in classes and attrs.get("hreflang"):
            self.alternates[attrs["hreflang"]] = href
        if tag == "link" and attrs.get("rel") == "alternate" and attrs.get("hreflang"):
            self.head_alternates[attrs["hreflang"]] = href
        if tag in ("a", "link"):
            self.hrefs.append((tag, attrs.get("rel"), href))
            if tag == "a" and self._nav_depth and "md-nav__link" in classes:
                self.nav_hrefs.append(href)

    def handle_endtag(self, tag):
        if tag in ("br", "img", "input", "link", "meta", "hr", "source", "path"):
            return
        if self._stack and self._stack.pop():
            self._nav_depth -= 1


def resolve(page_url, href):
    """Resolves a relative href against a page URL; returns None when it leaves the site."""
    path = urlsplit(href).path
    base = posixpath.dirname(page_url.rstrip("/") + "/x") if page_url else ""
    joined = posixpath.normpath(posixpath.join(base, unquote(path))) if path else page_url.rstrip("/")
    if joined == ".":
        joined = ""
    if joined.startswith("..") or joined.startswith("/"):
        return None
    if joined and (path.endswith("/") or posixpath.basename(path) in (".", "..")):
        joined += "/"
    return joined


def target_exists(site, target):
    full = os.path.join(site, target)
    if os.path.isdir(full):
        return os.path.isfile(os.path.join(full, "index.html"))
    return os.path.isfile(full)


def is_external(href):
    parts = urlsplit(href)
    return bool(parts.scheme) or href.startswith("//")


def counterpart(url, lang):
    return url[len("en/"):] if lang == "en" else "en/" + url


def check_site(site, failures):
    expected = expected_pages()
    built = {}
    for root, _dirs, names in os.walk(site):
        for name in names:
            rel = os.path.relpath(os.path.join(root, name), site).replace(os.sep, "/")
            if name.endswith(".md"):
                failures.append("Markdown source copied into the site: " + rel)
            if name.endswith(".html") and rel not in THEME_PAGES:
                built[file_url(rel)] = rel
    for url in sorted(set(built) - set(expected)):
        failures.append("page that is not a docs/api guide: " + built[url])
    for url in sorted(set(expected) - set(built)):
        failures.append("guide missing from the site: " + page_file(url))

    for url, lang in sorted(expected.items()):
        path = os.path.join(site, page_file(url))
        if not os.path.isfile(path):
            continue
        parser = LinkParser()
        with open(path, encoding="utf-8") as handle:
            html = handle.read()
        parser.feed(html)
        check_language_scripts(url, lang, html, parser, failures)
        check_language_selector(url, lang, parser, failures)
        check_navigation(url, lang, expected, parser, failures)
        check_links(site, url, parser, failures)

    for lang, prefix in LANGUAGES.items():
        check_search_index(site, lang, prefix, expected, failures)


def check_language_selector(url, lang, parser, failures):
    other = "en" if lang == "ko" else "ko"
    want = counterpart(url, lang)
    for code, target in ((other, want), (lang, url)):
        href = parser.alternates.get(code)
        if href is None:
            failures.append("%s: language selector has no %s entry" % (page_file(url), code))
        elif resolve(url, href) != target:
            failures.append("%s: %s selector points at %r, expected %r"
                            % (page_file(url), code, resolve(url, href), target))


def check_language_scripts(url, lang, html, parser, failures):
    """AC-6: home-page redirect, choice memory and hreflang alternates."""
    is_korean_home = url == ""
    if (REDIRECT_SCRIPT in html) != is_korean_home:
        failures.append("%s: the English-first redirect script belongs on the Korean home page only"
                        % page_file(url))
    if MEMORY_SCRIPT not in html:
        failures.append("%s: language choice memory script missing" % page_file(url))
    ko_url = url if lang == "ko" else counterpart(url, lang)
    want = {"ko": SITE_URL + ko_url, "en": SITE_URL + "en/" + ko_url, "x-default": SITE_URL + ko_url}
    for code, target in want.items():
        got = parser.head_alternates.get(code)
        if got != target:
            failures.append("%s: <link rel=alternate hreflang=%s> is %r, expected %r"
                            % (page_file(url), code, got, target))


def check_navigation(url, lang, expected, parser, failures):
    resolved = set()
    for href in parser.nav_hrefs:
        if is_external(href):
            resolved.add(href)
        elif not href.startswith("#"):
            resolved.add(resolve(url, href))
    if JAVADOC_URL not in resolved:
        failures.append("%s: navigation has no javadoc.io API reference link" % page_file(url))
    for page, page_lang in expected.items():
        if page_lang == lang and page not in resolved:
            failures.append("%s: navigation misses %s" % (page_file(url), page_file(page)))
        if page_lang != lang and page in resolved:
            failures.append("%s: navigation lists the other language's %s" % (page_file(url), page_file(page)))


def check_links(site, url, parser, failures):
    for tag, _rel, href in parser.hrefs:
        if is_external(href) or href.startswith("#") or href.startswith("mailto:"):
            continue
        path = urlsplit(href).path
        if path.endswith(".md"):
            failures.append("%s: link to a Markdown file %r" % (page_file(url), href))
            continue
        target = resolve(url, href)
        if target is None:
            failures.append("%s: link %r leaves the site" % (page_file(url), href))
        elif not target_exists(site, target):
            failures.append("%s: %s %r points at nothing in the site" % (page_file(url), tag, href))


def check_search_index(site, lang, prefix, expected, failures):
    index = os.path.join(site, prefix, "search", "search_index.json")
    if not os.path.isfile(index):
        failures.append("search index missing: %ssearch/search_index.json" % prefix)
        return
    with open(index, encoding="utf-8") as handle:
        docs = json.load(handle).get("docs", [])
    found = {prefix + d["location"].split("#", 1)[0] for d in docs}
    want = {url for url, page_lang in expected.items() if page_lang == lang}
    for url in sorted(want - found):
        failures.append("%s search index misses %s" % (lang, page_file(url)))
    for url in sorted(found - want):
        failures.append("%s search index holds %s, which is not a %s guide" % (lang, url or "/", lang))


def check_workflow(failures):
    if not os.path.isfile(WORKFLOW):
        failures.append("workflow missing: .github/workflows/docs-pages.yml")
        return
    import yaml  # installed by tools/docs-site/requirements.txt

    with open(WORKFLOW, encoding="utf-8") as handle:
        text = handle.read()
    data = yaml.safe_load(text)
    if re.search(r"gradle", text, re.IGNORECASE):
        failures.append("workflow mentions Gradle; the docs job must not run the Gradle build")
    triggers = data.get("on", data.get(True)) or {}
    push = triggers.get("push") or {}
    if push.get("branches") != ["main"]:
        failures.append("workflow must deploy on pushes to main only (on.push.branches: [main])")
    uses = []
    for name, job in (data.get("jobs") or {}).items():
        for step in job.get("steps") or []:
            if "uses" in step:
                uses.append((name, step["uses"]))
            run = step.get("run") or ""
            if "mkdocs build" in run and "--strict" not in run:
                failures.append("job %s runs mkdocs build without --strict" % name)
    for name, ref in uses:
        if not SHA_PIN.match(ref):
            failures.append("job %s uses %r, which is not pinned to a commit SHA" % (name, ref))
    refs = [ref.split("@", 1)[0] for _name, ref in uses]
    for action in ("actions/upload-pages-artifact", "actions/deploy-pages"):
        if action not in refs:
            failures.append("workflow does not use " + action)
    if "mkdocs build --strict" not in text:
        failures.append("workflow does not run mkdocs build --strict")
    if "node --test tools/docs-site/test_language_redirect.mjs" not in text:
        failures.append("workflow does not run tools/docs-site/test_language_redirect.mjs")
    if "tools/docs-site/check_site.py" not in text:
        failures.append("workflow does not run tools/docs-site/check_site.py")
    if "-r tools/docs-site/requirements.txt" not in text:
        failures.append("workflow does not install tools/docs-site/requirements.txt")
    for name, job in (data.get("jobs") or {}).items():
        deploys = any(str(s.get("uses", "")).startswith("actions/deploy-pages@") for s in job.get("steps") or [])
        if deploys and set(triggers) - {"push", "workflow_dispatch"}:
            condition = str(job.get("if", ""))
            if "github.event_name" not in condition:
                failures.append("deploy job %s must be limited to push and workflow_dispatch" % name)


def check_requirements(failures):
    with open(REQUIREMENTS, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.split("#", 1)[0].strip()
            if line and not re.match(r"^[A-Za-z0-9_.-]+==[A-Za-z0-9_.+-]+(\s*;.*)?$", line):
                failures.append("requirements.txt:%d is not pinned with ==: %s" % (number, line))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--site", default=os.path.join(REPO_ROOT, "build", "docs-site"))
    args = parser.parse_args(argv)
    failures = []
    if not os.path.isdir(args.site):
        failures.append("site directory missing: %s (run mkdocs build --strict first)" % args.site)
    else:
        check_site(args.site, failures)
    check_workflow(failures)
    check_requirements(failures)
    if failures:
        print("docs site check FAILED (%d):" % len(failures))
        for failure in failures:
            print("  - " + failure)
        return 1
    print("docs site check passed: %d guide pages, workflow and requirements pinned" % len(expected_pages()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
