// Tiny hash router: "#/employees/:id?tab=devices"

const routes = [];
let current = null;
let onChange = null;

function compile(pattern) {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/\//g, '\\/').replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^\\/]+)'; }) + '$');
  return { re, keys };
}

export function addRoute(pattern, screen, meta = {}) {
  routes.push({ pattern, screen, meta, ...compile(pattern) });
}

export function parseHash(hash = window.location.hash) {
  let h = hash.replace(/^#/, '');
  if (!h.startsWith('/')) h = '/' + h;
  const [pathPart, queryPart] = h.split('?');
  const query = {};
  if (queryPart) new URLSearchParams(queryPart).forEach((v, k) => { query[k] = v; });
  return { path: pathPart.replace(/\/+$/, '') || '/', query };
}

export function match(path) {
  for (const r of routes) {
    const m = r.re.exec(path);
    if (m) {
      const params = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { route: r, params };
    }
  }
  return null;
}

export function navigate(path, query) {
  let target = path;
  if (query && Object.keys(query).length) {
    const qs = new URLSearchParams();
    Object.entries(query).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.set(k, v); });
    const s = qs.toString();
    if (s) target += '?' + s;
  }
  if (window.location.hash === '#' + target) {
    dispatch();
  } else {
    window.location.hash = target;
  }
}

export function replaceQuery(query) {
  const { path } = parseHash();
  const qs = new URLSearchParams();
  Object.entries(query).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.set(k, v); });
  const s = qs.toString();
  const target = '#' + path + (s ? '?' + s : '');
  history.replaceState(null, '', target);
}

function dispatch() {
  const { path, query } = parseHash();
  const m = match(path);
  current = m ? { ...m, path, query } : { route: null, params: {}, path, query };
  if (onChange) onChange(current);
}

export function start(handler) {
  onChange = handler;
  window.addEventListener('hashchange', dispatch);
  dispatch();
}

export function currentRoute() { return current; }
export function allRoutes() { return routes.slice(); }
