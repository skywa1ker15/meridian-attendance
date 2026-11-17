// Login (admin, no device) + forgot password.

import { api, request } from '../api.js';
import { session } from '../session.js';
import { el, replace, button, field, input, formError, showFormError, setBusy, toast } from '../ui.js';

const DASHBOARD_ROLES = ['manager', 'admin', 'superadmin'];

function brand() {
  return el('div', { class: 'brand' }, el('div', { class: 'brand-mark' }, 'M'),
    el('div', { class: 'brand-text' }, el('strong', {}, 'Meridian Group'), el('span', {}, 'Attendance administration')));
}

function passwordInput(attrs = {}) {
  const inp = input({ type: 'password', autocomplete: 'current-password', required: true, ...attrs });
  const toggle = el('button', { type: 'button', class: 'toggle', 'aria-label': 'Show password' }, 'Show');
  toggle.addEventListener('click', () => {
    const show = inp.type === 'password';
    inp.type = show ? 'text' : 'password';
    toggle.textContent = show ? 'Hide' : 'Show';
    toggle.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
  });
  return { wrap: el('div', { class: 'password-wrap' }, inp, toggle), input: inp };
}

export { passwordInput };

export function render(container, { onSuccess }) {
  const page = el('div', { class: 'login-page' });
  replace(container, page);
  document.title = 'Sign in — Meridian Attendance';
  showLogin(page, onSuccess);
  return () => {};
}

function showLogin(page, onSuccess) {
  const identifier = input({ type: 'text', autocomplete: 'username', required: true, placeholder: 'Email or employee number', 'data-testid': 'login-identifier' });
  const pw = passwordInput({ 'data-testid': 'login-password' });
  const err = formError('');
  const submit = button('Sign in', { type: 'submit', attrs: { 'data-testid': 'login-submit' } });
  const forgot = el('button', { type: 'button', class: 'link-btn', 'data-testid': 'forgot-link' }, 'Forgot your password?');
  const form = el('form', { class: 'stack', novalidate: true },
    field('Identifier', identifier), field('Password', pw.wrap), err,
    el('div', { class: 'form-actions', style: { justifyContent: 'space-between', alignItems: 'center' } }, forgot, submit));
  const card = el('div', { class: 'card login-card' }, brand(),
    el('h1', {}, 'Sign in'), el('p', { class: 'muted' }, 'Management access only. Employees use the mobile app.'),
    form, el('div', { class: 'login-footer' }, 'Meridian Group · Umhlanga · Africa/Johannesburg'));
  replace(page, card);
  identifier.focus();

  forgot.addEventListener('click', () => showForgot(page, onSuccess));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(err, '');
    if (!identifier.value.trim() || !pw.input.value) { showFormError(err, 'Enter your identifier and password.'); return; }
    setBusy(submit, true, 'Signing in…');
    try {
      const data = await request('POST', '/auth/login', { body: { identifier: identifier.value.trim(), password: pw.input.value }, auth: false, retry: false });
      const user = data.user || {};
      if (!DASHBOARD_ROLES.includes(user.role)) {
        // Do not keep a session for an employee account; invalidate the tokens we were given.
        try { await fetch('/api/v1/auth/logout', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.access_token}` }, body: JSON.stringify({ refresh_token: data.refresh_token }) }); } catch (x) { /* ignore */ }
        showFormError(err, 'This account does not have dashboard access. Only managers and administrators can sign in here.');
        return;
      }
      session.setTokens(data.access_token, data.refresh_token);
      session.setUser({ ...user, must_change_password: Boolean(data.must_change_password || user.must_change_password) });
      if (data.must_change_password || user.must_change_password) toast('You must change your temporary password before continuing.', 'warning', 6000);
      onSuccess(session.user());
    } catch (ex) {
      if (ex.status === 423) showFormError(err, 'This account is temporarily locked after too many failed attempts. Try again in 15 minutes.');
      else if (ex.status === 429) showFormError(err, `Too many attempts. Please wait ${ex.retryAfter ? ex.retryAfter + ' seconds' : 'a moment'} and try again.`);
      else showFormError(err, ex);
      pw.input.focus();
    } finally {
      setBusy(submit, false, 'Signing in…');
    }
  });
}

function showForgot(page, onSuccess) {
  const identifier = input({ type: 'text', autocomplete: 'username', required: true, placeholder: 'Email or employee number', 'data-testid': 'forgot-identifier' });
  const err = formError('');
  const submit = button('Send reset link', { type: 'submit' });
  const back = button('Back to sign in', { kind: 'secondary', onClick: () => showLogin(page, onSuccess) });
  const form = el('form', { class: 'stack', novalidate: true }, field('Identifier', identifier), err, el('div', { class: 'form-actions' }, back, submit));
  const card = el('div', { class: 'card login-card' }, brand(), el('h1', {}, 'Forgot password'),
    el('p', { class: 'muted' }, 'If the account exists and email is configured, a reset link valid for 30 minutes will be sent. An administrator can also issue a temporary password.'),
    form);
  replace(page, card);
  identifier.focus();
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!identifier.value.trim()) { showFormError(err, 'Enter your identifier.'); return; }
    setBusy(submit, true, 'Sending…');
    try {
      await api.post('/auth/password/forgot', { identifier: identifier.value.trim() });
      replace(card, brand(), el('h1', {}, 'Check your email'),
        el('p', { class: 'muted', 'data-testid': 'forgot-done' }, 'If an account matches, a password reset email has been sent. The link expires in 30 minutes.'),
        el('div', { class: 'form-actions' }, button('Back to sign in', { onClick: () => showLogin(page, onSuccess) })));
    } catch (ex) {
      showFormError(err, ex);
    } finally { setBusy(submit, false, 'Sending…'); }
  });
}
