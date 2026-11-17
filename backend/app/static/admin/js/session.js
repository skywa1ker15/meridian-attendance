// Session: tokens + user kept in sessionStorage (cleared when the tab closes).
// The server enforces every permission; the dashboard only uses the list to
// decide what to show.

const KEY_ACCESS = 'mg.access';
const KEY_REFRESH = 'mg.refresh';
const KEY_USER = 'mg.user';

const listeners = new Set();

function read(key) {
  try { return window.sessionStorage.getItem(key); } catch (e) { return null; }
}
function write(key, value) {
  try {
    if (value === null || value === undefined) window.sessionStorage.removeItem(key);
    else window.sessionStorage.setItem(key, value);
  } catch (e) { /* storage unavailable (private mode) — session lives in memory only */ memory[key] = value; }
}
const memory = {};

function emit() { listeners.forEach((fn) => { try { fn(session); } catch (e) { console.error(e); } }); }

export const session = {
  accessToken() { return read(KEY_ACCESS) ?? memory[KEY_ACCESS] ?? null; },
  refreshToken() { return read(KEY_REFRESH) ?? memory[KEY_REFRESH] ?? null; },
  user() {
    const raw = read(KEY_USER) ?? memory[KEY_USER];
    if (!raw) return null;
    try { return JSON.parse(raw); } catch (e) { return null; }
  },
  isAuthenticated() { return Boolean(this.accessToken() && this.user()); },
  setTokens(access, refresh) { write(KEY_ACCESS, access); if (refresh) write(KEY_REFRESH, refresh); emit(); },
  setUser(user) { write(KEY_USER, JSON.stringify(user)); emit(); },
  clear() { write(KEY_ACCESS, null); write(KEY_REFRESH, null); write(KEY_USER, null); delete memory[KEY_ACCESS]; delete memory[KEY_REFRESH]; delete memory[KEY_USER]; emit(); },
  permissions() { const u = this.user(); return Array.isArray(u?.permissions) ? u.permissions : []; },
  can(permission) { return this.permissions().includes(permission); },
  canAny(...perms) { return perms.some((p) => this.can(p)); },
  role() { return this.user()?.role || null; },
  onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },
};
