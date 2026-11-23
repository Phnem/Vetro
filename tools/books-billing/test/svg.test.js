import assert from "node:assert/strict";
import { test } from "node:test";
import { sanitizeSignatureSvg, SvgError } from "../src/svg.js";

const NS = 'xmlns="http://www.w3.org/2000/svg"';
const good = `<svg ${NS} viewBox="0 0 300 120" fill="#1e3a8a"><path d="M10.5 20L30 40.2L10.5 60Z"/><circle cx="50.0" cy="60.0" r="2.40"/></svg>`;

const bad = (svg, why) => assert.throws(() => sanitizeSignatureSvg(svg), (e) => e instanceof SvgError, why);

test("accepts what the site's signature pad produces and returns the canonical string", () => {
  assert.equal(sanitizeSignatureSvg(good), good);
  assert.equal(sanitizeSignatureSvg("  \n" + good + "\n "), good);
});

test("accepts g, closing path tags, single quotes, stroke attributes", () => {
  const out = sanitizeSignatureSvg(
    `<svg ${NS} viewBox='0 0 10 10' width="100" height="40px"><g stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="bevel" fill="none"><path d='M0 0L10 10'></path></g></svg>`,
  );
  assert.match(out, /^<svg xmlns=/);
  assert.match(out, /<g stroke="currentColor"/);
  assert.match(out, /<path d="M0 0L10 10"\/>/);
});

test("rejects non-strings, empty and non-svg documents", () => {
  bad(undefined);
  bad(null);
  bad(42);
  bad("");
  bad("   ");
  bad("hello");
  bad("<div></div>");
  bad(`<path d="M0 0"/>`);
  bad(`${good}<svg ${NS} viewBox="0 0 1 1"><path d="M0 0"/></svg>`, "second root");
  bad(`${good} trailing`);
});

test("rejects script, foreignObject, style, use, image and other elements", () => {
  for (const el of [
    "<script>alert(1)</script>",
    "<script/>",
    "<foreignObject><div/></foreignObject>",
    "<style>*{fill:red}</style>",
    '<use xlink:href="#a"/>',
    '<use href="#a"/>',
    '<image href="https://evil.example/x.png"/>',
    "<a><path d='M0 0'/></a>",
    "<text>hi</text>",
    "<rect width='1' height='1'/>",
  ]) {
    bad(`<svg ${NS} viewBox="0 0 9 9">${el}<path d="M0 0"/></svg>`, el);
  }
});

test("rejects event handlers, style, href and unknown attributes", () => {
  bad(`<svg ${NS} viewBox="0 0 9 9" onload="alert(1)"><path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0" onclick="alert(1)"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0" style="fill:red"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0" href="https://evil.example"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0" xlink:href="#a"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0" id="a"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0" d="M1 1"/></svg>`, "duplicate attribute");
  bad(`<svg ${NS} viewBox="0 0 9 9"><circle d="M0 0" r="1"/></svg>`, "d on a circle");
});

test("rejects DOCTYPE, ENTITY, processing instructions, comments, CDATA and javascript:", () => {
  bad(`<!DOCTYPE svg>${good}`);
  bad(`<!DOCTYPE svg [<!ENTITY x "y">]>${good}`);
  bad(`<?xml version="1.0"?>${good}`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><!-- hi --><path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><![CDATA[x]]><path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9" fill="javascript:alert(1)"><path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9" fill="&#x6a;avascript:1"><path d="M0 0"/></svg>`, "entity in a value");
});

test("rejects bad values: d charset, colors, numbers, xmlns", () => {
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0 &lt;script&gt;"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0 X 5"/></svg>`, "letter outside the whitelist");
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="url(#a)"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9" fill="url(#a)"><path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9" fill="red;stroke:blue"><path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><circle cx="a" cy="1" r="1"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><circle cx="1" cy="1" r="-1"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9"><path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 0 0"><path d="M0 0"/></svg>`);
  bad(`<svg xmlns="http://evil.example/svg" viewBox="0 0 9 9"><path d="M0 0"/></svg>`);
  bad(`<svg viewBox="0 0 9 9"><path d="M0 0"/></svg>`, "xmlns required");
  bad(`<svg ${NS}><path d="M0 0"/></svg>`, "viewBox required");
});

test("rejects structural tricks: text, unclosed, mismatched, nesting, drawn nothing", () => {
  bad(`<svg ${NS} viewBox="0 0 9 9">text<path d="M0 0"/></svg>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0"/>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><g><path d="M0 0"/></svg></g>`);
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="M0 0"><path d="M1 1"/></path></svg>`, "children inside path");
  bad(`<svg ${NS} viewBox="0 0 9 9"></svg>`, "nothing drawn");
  bad(`<svg ${NS} viewBox="0 0 9 9"><g></g></svg>`, "nothing drawn in g");
  bad(`<svg${NS} viewBox="0 0 9 9"><path d="M0 0"/></svg>`, "no space between the tag name and the first attribute");
  bad(`<svg ${NS}viewBox="0 0 9 9"><path d="M0 0"/></svg>`, "no space between attributes");
  bad("<svg ".repeat(10) + "x");
  bad(`<svg ${NS} viewBox="0 0 9 9">${"<g>".repeat(8)}<path d="M0 0"/>${"</g>".repeat(8)}</svg>`, "too deep");
});

test("rejects control characters and oversize input", () => {
  bad(good.replace("M10.5", "M10.5\u0000"));
  bad(`<svg ${NS} viewBox="0 0 9 9"><path d="${"M0 0 ".repeat(40_000)}"/></svg>`, "over 200 KB");
});

test("the canonical output never contains what the parser forbids", () => {
  const out = sanitizeSignatureSvg(`<svg ${NS} viewBox="0 0 9 9"><g fill="red"><path d="M0 0L1 1"/></g></svg>`);
  assert.doesNotMatch(out, /script|href|style|on\w+=|<!|<\?/i);
});
