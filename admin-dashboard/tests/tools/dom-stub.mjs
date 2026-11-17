// Minimal browser globals so the dashboard's ES modules can be imported in Node.
// Only what module top-level code and the pure helpers touch is provided.
class MemStorage {
  constructor() { this.m = new Map(); }
  getItem(k) { return this.m.has(k) ? this.m.get(k) : null; }
  setItem(k, v) { this.m.set(k, String(v)); }
  removeItem(k) { this.m.delete(k); }
  clear() { this.m.clear(); }
}
function fakeNode() {
  const n = {
    style: {}, dataset: {}, classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    childNodes: [], appendChild(c) { n.childNodes.push(c); return c; }, removeChild() {}, setAttribute() {}, removeAttribute() {},
    addEventListener() {}, removeEventListener() {}, querySelector() { return null; }, querySelectorAll() { return []; }, focus() {},
  };
  return n;
}
export function installDomStub(hash = '') {
  const listeners = {};
  const g = globalThis;
  g.window = g;
  g.location = { hash, origin: 'http://127.0.0.1:8000', href: 'http://127.0.0.1:8000/admin/' + hash };
  g.history = { replaceState(_s, _t, url) { g.location.hash = url; } };
  g.sessionStorage = new MemStorage();
  g.localStorage = new MemStorage();
  g.addEventListener = (t, fn) => { (listeners[t] ||= []).push(fn); };
  g.removeEventListener = () => {};
  g.dispatchEvent = (e) => { (listeners[e.type] || []).forEach((fn) => fn(e)); return true; };
  if (!g.CustomEvent) g.CustomEvent = class extends Event { constructor(t, o = {}) { super(t); this.detail = o.detail; } };
  g.matchMedia = () => ({ matches: false, addEventListener() {} });
  g.document = {
    title: '', visibilityState: 'visible', body: fakeNode(), activeElement: null,
    getElementById: () => fakeNode(), createElement: () => fakeNode(), createElementNS: () => fakeNode(),
    createTextNode: (t) => ({ textContent: t }), createDocumentFragment: () => fakeNode(), createRange: () => ({ selectNodeContents() {} }),
    addEventListener() {}, removeEventListener() {}, querySelector: () => null, querySelectorAll: () => [],
  };
  g.Node = class {};
  g.fetch = async () => { throw new Error('network disabled in tests'); };
  return { listeners };
}
