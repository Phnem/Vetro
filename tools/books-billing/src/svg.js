// Подпись договора приходит с сайта как SVG, но запрос можно собрать руками, поэтому SVG — недоверенный ввод.
// Санитайзер не ищет «плохое» регэкспом: он РАЗБИРАЕТ документ по белому списку (элементы, атрибуты, значения) и собирает
// каноническую строку заново. Любое отклонение — SvgError (на выходе 400 bad_signature), «починки» нет.
// Панель показывает подпись только как <img src="data:image/svg+xml;base64,...">, не как inline-SVG.

export class SvgError extends Error {}

const MAX_BYTES = 200 * 1024;
const MAX_ELEMENTS = 5000;
const MAX_DEPTH = 6;
const MAX_PATH = 150_000;
const SVG_NS = "http://www.w3.org/2000/svg";

const PAINT = ["fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin"];
const ALLOWED = {
  svg: ["xmlns", "viewBox", "width", "height", ...PAINT],
  g: [...PAINT],
  path: ["d", ...PAINT],
  circle: ["cx", "cy", "r", ...PAINT],
};

const NUM = /^-?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i;
const COLOR = [
  /^(none|currentColor)$/,
  /^#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$/i,
  /^[a-z]{3,20}$/i, // именованные цвета; url(...) сюда не пройдёт: скобок нет
  /^(rgb|rgba|hsl|hsla)\([0-9.,%\s/]{1,40}\)$/i,
];
const PATH_CHARS = /^[MmLlHhVvCcSsQqTtAaZz0-9eE+\-., ]*$/;

const VALIDATORS = {
  xmlns: (v) => v === SVG_NS,
  viewBox: (v) => {
    const p = v.trim().split(/[\s,]+/);
    return p.length === 4 && p.every((x) => NUM.test(x)) && Number(p[2]) > 0 && Number(p[3]) > 0;
  },
  width: (v) => /^(\d+\.?\d*|\.\d+)(px)?$/.test(v),
  height: (v) => /^(\d+\.?\d*|\.\d+)(px)?$/.test(v),
  d: (v) => v.length <= MAX_PATH && PATH_CHARS.test(v),
  cx: (v) => NUM.test(v),
  cy: (v) => NUM.test(v),
  r: (v) => NUM.test(v) && Number(v) >= 0,
  fill: (v) => COLOR.some((re) => re.test(v)),
  stroke: (v) => COLOR.some((re) => re.test(v)),
  "stroke-width": (v) => NUM.test(v) && Number(v) >= 0,
  "stroke-linecap": (v) => ["butt", "round", "square"].includes(v),
  "stroke-linejoin": (v) => ["miter", "round", "bevel"].includes(v),
};

const fail = (why) => {
  throw new SvgError(why);
};

const isWs = (c) => c === 32 || c === 9 || c === 10 || c === 13;
const isNameStart = (c) => (c >= 97 && c <= 122) || (c >= 65 && c <= 90);
const isNameChar = (c) => isNameStart(c) || c === 45;

/** Разобрать и пересобрать SVG подписи. Возвращает каноническую строку или бросает SvgError. */
export function sanitizeSignatureSvg(input) {
  if (typeof input !== "string") fail("type");
  if (input.length > MAX_BYTES || new TextEncoder().encode(input).length > MAX_BYTES) fail("size");
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(input)) fail("control");
  const s = input.trim();
  if (!s) fail("empty");
  // Декларации, комментарии, CDATA, инструкции и javascript: не нужны вовсе. Это ранний отказ, а не защита: защита — разбор ниже.
  if (/<[!?]/.test(s) || /javascript:/i.test(s)) fail("forbidden");

  let i = 0;
  let count = 0;
  const skipWs = () => {
    while (i < s.length && isWs(s.charCodeAt(i))) i += 1;
  };

  function readName() {
    const start = i;
    if (i >= s.length || !isNameStart(s.charCodeAt(i))) fail("name");
    while (i < s.length && isNameChar(s.charCodeAt(i))) i += 1;
    return s.slice(start, i);
  }

  function readAttrs(el) {
    const attrs = [];
    const seen = new Set();
    for (;;) {
      const hadWs = i < s.length && isWs(s.charCodeAt(i));
      skipWs();
      if (i >= s.length) fail("eof");
      const c = s[i];
      if (c === ">" || c === "/") return attrs;
      if (!hadWs) fail("attr-sep");
      const name = readName();
      skipWs();
      if (s[i] !== "=") fail("attr-eq");
      i += 1;
      skipWs();
      const q = s[i];
      if (q !== '"' && q !== "'") fail("attr-quote");
      const end = s.indexOf(q, i + 1);
      if (end < 0) fail("attr-end");
      const value = s.slice(i + 1, end);
      i = end + 1;
      if (/[<>&]/.test(value)) fail("attr-value");
      if (!ALLOWED[el].includes(name)) fail("attr-name");
      if (seen.has(name)) fail("attr-dup");
      seen.add(name);
      if (!VALIDATORS[name](value)) fail("attr-bad-" + name);
      attrs.push([name, value]);
    }
  }

  // Возвращает узел; i стоит на «<» открывающего тега.
  function readElement(depth) {
    if (depth > MAX_DEPTH) fail("depth");
    if (s[i] !== "<") fail("expected-tag");
    i += 1;
    const name = readName();
    if (!Object.hasOwn(ALLOWED, name)) fail("element");
    count += 1;
    if (count > MAX_ELEMENTS) fail("count");
    const attrs = readAttrs(name);
    const node = { name, attrs, children: [] };
    if (s[i] === "/") {
      if (s[i + 1] !== ">") fail("self-close");
      i += 2;
      return node;
    }
    i += 1; // «>»
    if (name === "path" || name === "circle") {
      // Без детей: допускается только сразу закрывающий тег.
      skipWs();
      readClose(name);
      return node;
    }
    for (;;) {
      skipWs();
      if (i >= s.length) fail("eof");
      if (s[i] !== "<") fail("text");
      if (s[i + 1] === "/") {
        readClose(name);
        return node;
      }
      node.children.push(readElement(depth + 1));
    }
  }

  function readClose(name) {
    if (s[i] !== "<" || s[i + 1] !== "/") fail("close");
    i += 2;
    if (readName() !== name) fail("close-name");
    skipWs();
    if (s[i] !== ">") fail("close-end");
    i += 1;
  }

  skipWs();
  const root = readElement(0);
  skipWs();
  if (i !== s.length) fail("trailing");
  if (root.name !== "svg") fail("root");
  if (!root.attrs.some(([n]) => n === "xmlns")) fail("xmlns-missing");
  if (!root.attrs.some(([n]) => n === "viewBox")) fail("viewbox-missing");

  let drawn = 0;
  const walk = (n) => {
    if (n.name === "path" || n.name === "circle") drawn += 1;
    n.children.forEach(walk);
  };
  walk(root);
  if (drawn === 0) fail("blank");

  const out = (n) => {
    const a = n.attrs.map(([k, v]) => ` ${k}="${v}"`).join("");
    if (n.name === "path" || n.name === "circle") return `<${n.name}${a}/>`;
    return `<${n.name}${a}>${n.children.map(out).join("")}</${n.name}>`;
  };
  return out(root);
}
