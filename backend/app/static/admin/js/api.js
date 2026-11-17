// Same-origin API client for /api/v1 (see docs/API.md).
// - Bearer access token on every request
// - single-flight refresh-token rotation on 401, then one retry
// - uniform ApiError with code/message/details
// - file downloads through fetch + Blob (so the bearer token is sent)

import { session } from './session.js';
import { uuid } from './format.js';

export const API_BASE = '/api/v1';

export class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details || {};
  }
  get isNetwork() { return this.status === 0; }
}

async function toError(res) {
  let payload = null;
  try { payload = await res.json(); } catch (e) { /* non-JSON body */ }
  const err = (payload && payload.error) || {};
  const fallback = {
    401: 'Your session has expired. Please sign in again.',
    403: 'You do not have permission to do this.',
    404: 'Not found.',
    409: 'This conflicts with an existing record.',
    423: 'This account is temporarily locked.',
    429: 'Too many requests. Please wait a moment and try again.',
    500: 'The server encountered an error. Please try again.',
  }[res.status] || `Request failed (${res.status}).`;
  const e = new ApiError(res.status, err.code || (res.status === 429 ? 'rate_limited' : 'http_error'), err.message || fallback, err.details);
  if (res.status === 429) e.retryAfter = Number(res.headers.get('Retry-After')) || null;
  return e;
}

export function buildUrl(path, query) {
  const url = new URL(API_BASE + path, window.location.origin);
  if (query) {
    Object.entries(query).forEach(([k, v]) => {
      if (v === undefined || v === null || v === '') return;
      url.searchParams.set(k, String(v));
    });
  }
  return url.toString();
}

let refreshing = null;

export async function refreshSession() {
  if (!refreshing) {
    refreshing = (async () => {
      const rt = session.refreshToken();
      if (!rt) throw new ApiError(401, 'token_invalid', 'Not signed in.');
      let res;
      try {
        res = await fetch(buildUrl('/auth/refresh'), {
          method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ refresh_token: rt }),
        });
      } catch (e) { throw new ApiError(0, 'network_error', 'Cannot reach the server.'); }
      if (!res.ok) throw await toError(res);
      const data = await res.json();
      session.setTokens(data.access_token, data.refresh_token);
      if (data.user) session.setUser(data.user);
      return data;
    })().finally(() => { refreshing = null; });
  }
  return refreshing;
}

function expireSession(err) {
  session.clear();
  window.dispatchEvent(new CustomEvent('session:expired', { detail: err }));
}

export async function request(method, path, opts = {}) {
  const { body, query, raw = false, auth = true, retry = true, headers = {} } = opts;
  const init = { method, headers: { Accept: 'application/json', ...headers } };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  if (method !== 'GET' && method !== 'HEAD') init.headers['Idempotency-Key'] = opts.idempotencyKey || uuid();
  if (auth && session.accessToken()) init.headers.Authorization = `Bearer ${session.accessToken()}`;

  let res;
  try {
    res = await fetch(buildUrl(path, query), init);
  } catch (e) {
    throw new ApiError(0, 'network_error', 'Cannot reach the server. Check your connection and try again.');
  }

  if (res.status === 401 && auth && retry) {
    let code = null;
    try { code = (await res.clone().json())?.error?.code || null; } catch (e) { /* ignore */ }
    if (code === 'token_expired' || code === 'token_invalid' || code === null) {
      try {
        await refreshSession();
      } catch (e) {
        expireSession(e);
        throw e;
      }
      return request(method, path, { ...opts, retry: false, idempotencyKey: init.headers['Idempotency-Key'] });
    }
    const err = await toError(res);
    expireSession(err);
    throw err;
  }

  if (!res.ok) throw await toError(res);
  if (raw) return res;
  if (res.status === 204 || res.headers.get('content-length') === '0') return null;
  const text = await res.text();
  if (!text) return null;
  return JSON.parse(text);
}

function filenameFrom(res, fallback) {
  const cd = res.headers.get('Content-Disposition') || '';
  const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(cd);
  if (star) { try { return decodeURIComponent(star[1].replace(/"/g, '').trim()); } catch (e) { /* ignore */ } }
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return plain ? plain[1].trim() : fallback;
}

export const api = {
  get: (path, query) => request('GET', path, { query }),
  post: (path, body = {}, query) => request('POST', path, { body, query }),
  patch: (path, body = {}, query) => request('PATCH', path, { body, query }),
  delete: (path, query) => request('DELETE', path, { query }),

  /** Download a file endpoint with the bearer token; triggers a browser download. Returns the filename. */
  async download(path, query, fallbackName = 'download') {
    const res = await request('GET', path, { query, raw: true });
    const blob = await res.blob();
    const name = filenameFrom(res, fallbackName);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = name; a.rel = 'noopener'; a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 15000);
    return name;
  },

  /** Fetch as JSON object and offer it as a .json download (POPIA export). */
  async downloadJson(path, query, filename) {
    const data = await request('GET', path, { query });
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename; a.style.display = 'none';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 15000);
    return filename;
  },
};

/** Fetch every page of a paginated endpoint (bounded). */
export async function fetchAll(path, query = {}, maxPages = 20) {
  const items = [];
  let page = 1;
  for (;;) {
    const data = await api.get(path, { ...query, page, page_size: 200 });
    items.push(...(data.items || []));
    if (items.length >= (data.total || 0) || !(data.items || []).length || page >= maxPages) break;
    page += 1;
  }
  return items;
}
