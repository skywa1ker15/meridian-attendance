// Application shell: authentication gate, navigation rail, top bar, routing.

import { session } from './session.js';
import { api } from './api.js';
import { addRoute, start, navigate, currentRoute } from './router.js';
import { el, icon, clear, replace, toast, button, errorMessage } from './ui.js';
import { fullName, initials, titleCase } from './format.js';

import * as login from './screens/login.js';
import * as resetPassword from './screens/reset-password.js';
import * as changePassword from './screens/change-password.js';
import * as overview from './screens/overview.js';
import * as employees from './screens/employees.js';
import * as employeeProfile from './screens/employee-profile.js';
import * as live from './screens/live.js';
import * as records from './screens/records.js';
import * as pending from './screens/pending.js';
import * as exceptions from './screens/exceptions.js';
import * as reports from './screens/reports.js';
import * as workplace from './screens/workplace.js';
import * as devices from './screens/devices.js';
import * as roles from './screens/roles.js';
import * as audit from './screens/audit.js';
import * as broadcast from './screens/broadcast.js';

export const NAV = [
  { path: '/overview', label: 'Overview', icon: 'dashboard', screen: overview, permission: 'admin:read' },
  { path: '/employees', label: 'Employees', icon: 'users', screen: employees, permission: 'admin:read' },
  { path: '/live', label: 'Live attendance', icon: 'live', screen: live, permission: 'admin:read' },
  { path: '/records', label: 'Attendance records', icon: 'records', screen: records, permission: 'admin:read' },
  { path: '/pending', label: 'Pending confirmations', icon: 'pending', screen: pending, permission: 'attendance:review', badge: true },
  { path: '/exceptions', label: 'Exceptions', icon: 'exceptions', screen: exceptions, permission: 'admin:read' },
  { path: '/reports', label: 'Reports & analytics', icon: 'reports', screen: reports, permission: 'admin:read' },
  { path: '/workplaces', label: 'Workplace configuration', icon: 'workplace', screen: workplace, permission: 'admin:read' },
  { path: '/devices', label: 'Devices', icon: 'devices', screen: devices, permission: 'admin:read' },
  { path: '/roles', label: 'Roles & permissions', icon: 'roles', screen: roles, permission: 'admin:read' },
  { path: '/audit', label: 'Audit logs', icon: 'audit', screen: audit, permission: 'admin:read' },
  { path: '/notifications-broadcast', label: 'Broadcast notification', icon: 'bell', screen: broadcast, permission: 'notifications:broadcast' },
];

const EXTRA_ROUTES = [
  { path: '/employees/:id', screen: employeeProfile, permission: 'admin:read', parent: '/employees' },
  { path: '/workplace', screen: null, redirect: '/workplaces' },
  { path: '/change-password', screen: changePassword, permission: null },
  { path: '/login', screen: login, permission: null, public: true },
  { path: '/reset-password', screen: resetPassword, permission: null, public: true },
];

const root = document.getElementById('app');
let activeCleanup = null;
let badgeTimer = null;
let shellEls = null;

function registerRoutes() {
  NAV.forEach((n) => addRoute(n.path, n.screen, n));
  EXTRA_ROUTES.forEach((n) => addRoute(n.path, n.screen, n));
  addRoute('/', null, { redirect: '/overview' });
}

function runCleanup() {
  if (typeof activeCleanup === 'function') { try { activeCleanup(); } catch (e) { console.error(e); } }
  activeCleanup = null;
}

function renderPublic(screen, r) {
  runCleanup();
  shellEls = null;
  clear(root);
  const container = el('div');
  root.appendChild(container);
  activeCleanup = screen.render(container, { onSuccess: afterLogin, query: r.query, navigate });
}

function afterLogin(user) {
  if (user?.must_change_password) { navigate('/change-password'); return; }
  const target = sessionStorage.getItem('mg.redirect') || '/overview';
  sessionStorage.removeItem('mg.redirect');
  navigate(target.startsWith('/') && !target.startsWith('/login') ? target : '/overview');
}

function buildShell() {
  const user = session.user();
  const rail = el('nav', { class: 'rail', 'aria-label': 'Main navigation' },
    el('div', { class: 'brand' }, el('div', { class: 'brand-mark' }, 'M'), el('div', { class: 'brand-text' }, el('strong', {}, 'Meridian Group'), el('span', {}, 'Attendance administration'))));
  const navLinks = {};
  NAV.filter((n) => session.can(n.permission)).forEach((n) => {
    const a = el('a', { class: 'nav-item', href: `#${n.path}`, title: n.label }, icon(n.icon, 20), el('span', { class: 'nav-label' }, n.label));
    if (n.badge) a.appendChild(el('span', { class: 'nav-badge hidden', dataset: { badge: n.path } }));
    a.addEventListener('click', () => closeMobileNav());
    navLinks[n.path] = a;
    rail.appendChild(a);
  });
  rail.appendChild(el('div', { class: 'rail-spacer' }));
  const toggle = el('button', { class: 'rail-toggle', type: 'button', 'aria-label': 'Collapse navigation', title: 'Collapse / expand navigation' }, icon('menu', 18));
  rail.appendChild(el('div', { class: 'rail-footer' }, toggle, el('span', { class: 'rail-footer-text' }, 'v1.0 · Africa/Johannesburg')));

  const title = el('div', { class: 'topbar-titles' }, el('div', { class: 'topbar-title' }, ''), el('div', { class: 'topbar-sub' }, ''));
  const menuBtn = el('button', { class: 'nav-open-btn', type: 'button', 'aria-label': 'Open navigation', 'aria-expanded': 'false' }, icon('menu', 20));
  const logoutBtn = button('Sign out', { kind: 'secondary', size: 'sm', icon: 'logout', onClick: logout, attrs: { 'data-testid': 'logout' } });
  const userChip = el('div', { class: 'user-chip', title: `${fullName(user)} · ${user?.role || ''}` },
    el('div', { class: 'avatar' }, initials(user)),
    el('div', {}, el('div', { class: 'name' }, fullName(user)), el('div', { class: 'role' }, titleCase(user?.role || ''))));
  const topbar = el('header', { class: 'topbar' }, menuBtn, title, el('div', { class: 'topbar-spacer' }), userChip, logoutBtn);
  const content = el('main', { class: 'content', id: 'main', tabindex: '-1' });
  const main = el('div', { class: 'main' }, topbar, content);
  const backdrop = el('div', { class: 'rail-backdrop', onClick: () => closeMobileNav() });
  const shell = el('div', { class: `shell ${localStorage.getItem('mg.rail') === 'collapsed' ? 'rail-collapsed' : ''}` }, rail, backdrop, main);
  toggle.addEventListener('click', () => {
    shell.classList.toggle('rail-collapsed');
    localStorage.setItem('mg.rail', shell.classList.contains('rail-collapsed') ? 'collapsed' : 'open');
  });
  menuBtn.addEventListener('click', () => {
    const open = !shell.classList.contains('nav-open');
    shell.classList.toggle('nav-open', open);
    menuBtn.setAttribute('aria-expanded', String(open));
    if (open) (rail.querySelector('.nav-item') || toggle).focus();
  });
  clear(root);
  root.appendChild(shell);
  shellEls = { shell, rail, content, navLinks, title, menuBtn };
  startBadgePolling();
}

function closeMobileNav() {
  if (!shellEls) return;
  shellEls.shell.classList.remove('nav-open');
  shellEls.menuBtn.setAttribute('aria-expanded', 'false');
}

function setTitle(t, sub = '') {
  if (!shellEls) return;
  shellEls.title.firstChild.textContent = t;
  shellEls.title.lastChild.textContent = sub;
  document.title = `${t} — Meridian Attendance`;
}

function setActiveNav(path) {
  if (!shellEls) return;
  Object.entries(shellEls.navLinks).forEach(([p, a]) => {
    const active = p === path;
    a.classList.toggle('active', active);
    if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
}

async function refreshBadge() {
  if (!shellEls || !session.can('attendance:review')) return;
  try {
    const ov = await api.get('/admin/overview');
    const n = (ov.stats?.pending_confirmations || 0) + (ov.stats?.under_review || 0) + (ov.stats?.pending_requests || 0);
    const b = shellEls.rail.querySelector('[data-badge="/pending"]');
    if (b) { b.textContent = String(n); b.classList.toggle('hidden', n === 0); }
  } catch (e) { /* non-critical */ }
}
function startBadgePolling() {
  clearInterval(badgeTimer);
  refreshBadge();
  badgeTimer = setInterval(refreshBadge, 60000);
}

async function logout() {
  const rt = session.refreshToken();
  try { if (rt) await api.post('/auth/logout', { refresh_token: rt }); } catch (e) { /* ignore */ }
  session.clear();
  clearInterval(badgeTimer);
  sessionStorage.removeItem('mg.redirect');
  toast('Signed out.', 'info', 2500);
  navigate('/login');
}

function renderNoAccess(container) {
  replace(container, el('div', { class: 'center-page' },
    el('div', { class: 'card stack' },
      el('h2', {}, 'No dashboard access'),
      el('p', { class: 'muted' }, `This account (${session.user()?.email || ''}) has the role "${session.role()}", which does not include administration permissions. Ask an administrator to grant you the manager or admin role.`),
      el('div', { class: 'form-actions' }, button('Sign out', { kind: 'secondary', icon: 'logout', onClick: logout })))));
}

function onRoute(r) {
  const authed = session.isAuthenticated();
  const meta = r.route?.meta || {};

  if (meta.redirect) { navigate(meta.redirect, r.query); return; }

  if (meta.public) {
    if (r.path === '/login' && authed) { afterLogin(session.user()); return; }
    renderPublic(r.route.screen, r);
    return;
  }

  if (!authed) {
    const q = Object.keys(r.query).length ? '?' + new URLSearchParams(r.query) : '';
    if (r.path !== '/' ) sessionStorage.setItem('mg.redirect', r.path + q);
    navigate('/login');
    return;
  }

  const user = session.user();
  if (user?.must_change_password && r.path !== '/change-password') { navigate('/change-password'); return; }

  if (!shellEls) buildShell();
  runCleanup();
  closeMobileNav();
  const { content } = shellEls;
  clear(content);
  setActiveNav(meta.parent || r.path);

  if (!r.route || !r.route.screen) {
    setTitle('Not found');
    replace(content, el('div', { class: 'state' }, el('strong', {}, 'Page not found'), el('a', { href: '#/overview' }, 'Go to overview')));
    return;
  }
  if (r.path !== '/change-password' && !session.can('admin:read')) {
    setTitle('No access');
    renderNoAccess(content);
    return;
  }
  if (meta.permission && !session.can(meta.permission)) {
    setTitle('Not allowed');
    replace(content, el('div', { class: 'state' }, el('strong', {}, 'You do not have permission to view this page.'), el('a', { href: '#/overview' }, 'Go to overview')));
    return;
  }
  const ctx = { params: r.params, query: r.query, setTitle, navigate, user, refreshBadge, logout };
  try {
    const result = r.route.screen.render(content, ctx);
    if (result && typeof result.then === 'function') {
      result.then((cleanup) => { activeCleanup = cleanup; }).catch((err) => {
        console.error(err);
        replace(content, el('div', { class: 'state error' }, el('strong', {}, 'This page failed to load'), el('p', {}, errorMessage(err))));
      });
    } else {
      activeCleanup = result;
    }
  } catch (err) {
    console.error(err);
    replace(content, el('div', { class: 'state error' }, el('strong', {}, 'This page failed to load'), el('p', {}, errorMessage(err))));
  }
  content.focus({ preventScroll: true });
}

window.addEventListener('session:expired', () => {
  clearInterval(badgeTimer);
  shellEls = null;
  const r = currentRoute();
  if (r && r.path && !r.route?.meta?.public) {
    const q = Object.keys(r.query).length ? '?' + new URLSearchParams(r.query) : '';
    sessionStorage.setItem('mg.redirect', r.path + q);
  }
  toast('Your session has expired. Please sign in again.', 'warning');
  navigate('/login');
});

function fingerprint() {
  const u = session.user();
  return u ? `${u.id}|${u.role}|${(u.permissions || []).join(',')}|${u.must_change_password ? 1 : 0}` : '';
}
let lastFingerprint = fingerprint();
session.onChange(() => {
  // Rebuild the shell only when role/permissions actually change (not on every token refresh).
  const fp = fingerprint();
  if (fp === lastFingerprint) return;
  lastFingerprint = fp;
  if (session.isAuthenticated() && shellEls) {
    const r = currentRoute();
    if (r && !r.route?.meta?.public) { shellEls = null; onRoute(r); }
  }
});

registerRoutes();
start(onRoute);
