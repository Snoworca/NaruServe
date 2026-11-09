// Tests the language scripts shipped in the built site (OPS-DX-005 AC-6).
//
// Run from the repository root after both mkdocs builds:
//   node --test tools/docs-site/test_language_redirect.mjs
// The scripts are read out of the built HTML, so the test exercises exactly
// what GitHub Pages serves. Node's standard library only.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const repo = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const site = process.env.DOCS_SITE_DIR || join(repo, "build", "docs-site");
const KEY = "naruserve.lang";

function scriptById(page, id) {
  const html = readFileSync(join(site, page), "utf8");
  const match = html.match(new RegExp(`<script id="${id}">([\\s\\S]*?)</script>`));
  return match ? match[1] : null;
}

function storage(initial, { throws = false } = {}) {
  const data = new Map(Object.entries(initial || {}));
  const fail = () => { throw new Error("SecurityError"); };
  return {
    data,
    getItem: (k) => (throws ? fail() : data.has(k) ? data.get(k) : null),
    setItem: (k, v) => (throws ? fail() : data.set(k, String(v))),
  };
}

// Runs the home-page redirect script; returns the URL passed to
// location.replace, or null when the visitor stays on the Korean page.
function runRedirect({ languages, language, stored, throws, search = "", hash = "" }) {
  const code = scriptById("index.html", "naruserve-lang-redirect");
  assert.ok(code, "the Korean home page carries the redirect script");
  let replaced = null;
  const navigator = languages === undefined ? { language } : { languages, language };
  const context = {
    navigator,
    localStorage: storage(stored === undefined ? {} : { [KEY]: stored }, { throws }),
    location: { search, hash, replace: (url) => { replaced = url; } },
  };
  vm.runInNewContext(code, context);
  return replaced;
}

test("an English browser is sent to the English home page", () => {
  assert.equal(runRedirect({ languages: ["en-US", "en"] }), "./en/");
});

test("a Korean browser is also sent to the English home page", () => {
  assert.equal(runRedirect({ languages: ["ko-KR", "ko", "en-US"] }), "./en/");
  assert.equal(runRedirect({ languages: ["ko-KR"] }), "./en/");
});

test("a browser with neither language gets English", () => {
  assert.equal(runRedirect({ languages: ["ja-JP", "ja"] }), "./en/");
  assert.equal(runRedirect({ languages: undefined, language: "fr-FR" }), "./en/");
});

test("choosing 한국어 in the selector keeps the Korean home page", () => {
  assert.equal(runRedirect({ languages: ["en-US"], stored: "ko" }), null);
  assert.equal(runRedirect({ languages: ["ko-KR"], stored: "ko" }), null);
});

test("a remembered English choice goes to English", () => {
  assert.equal(runRedirect({ languages: ["ko-KR"], stored: "en" }), "./en/");
});

test("an unknown remembered value is ignored", () => {
  assert.equal(runRedirect({ languages: ["ko-KR"], stored: "fr" }), "./en/");
});

test("throwing storage still sends the visitor to English", () => {
  assert.equal(runRedirect({ languages: ["en-US"], throws: true }), "./en/");
  assert.equal(runRedirect({ languages: ["ko-KR"], throws: true }), "./en/");
});

test("the query and hash are kept", () => {
  assert.equal(runRedirect({ languages: ["en"], search: "?q=1", hash: "#quick-start" }),
               "./en/?q=1#quick-start");
});

// Runs the choice-memory script of a page and clicks a link.
function clickOn(page, target, store) {
  const code = scriptById(page, "naruserve-lang-memory");
  assert.ok(code, `${page} carries the choice-memory script`);
  let handler = null;
  const document = { addEventListener: (type, fn) => { if (type === "click") handler = fn; } };
  vm.runInNewContext(code, { document, localStorage: store });
  assert.ok(handler, "a click listener is registered");
  handler({ target });
}

function link(classes, hreflang) {
  const self = {
    closest: (selector) =>
      selector === "a.md-select__link[hreflang]" && classes.includes("md-select__link") && hreflang
        ? self : null,
    getAttribute: (name) => (name === "hreflang" ? hreflang : null),
  };
  return self;
}

for (const page of ["index.html", "auth/index.html", "en/index.html", "en/auth/index.html"]) {
  test(`choosing a language in the selector on ${page} is remembered`, () => {
    const store = storage({});
    clickOn(page, link(["md-select__link"], "ko"), store);
    assert.equal(store.data.get(KEY), "ko");
    clickOn(page, link(["md-select__link"], "en"), store);
    assert.equal(store.data.get(KEY), "en");
  });
}

test("other clicks are not remembered and throwing storage is ignored", () => {
  const store = storage({});
  clickOn("auth/index.html", link(["md-nav__link"], null), store);
  assert.equal(store.data.size, 0);
  clickOn("auth/index.html", link(["md-select__link"], "en"), storage({}, { throws: true }));
});
