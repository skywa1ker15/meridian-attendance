// #/reset-password?token=… — public screen reached from the emailed link.

import { api } from '../api.js';
import { el, replace, button, field, formError, showFormError, setBusy, toast } from '../ui.js';
import { passwordInput } from './login.js';

export function render(container, { query, navigate }) {
  const token = query.token || '';
  document.title = 'Reset password — Meridian Attendance';
  const page = el('div', { class: 'login-page' });
  replace(container, page);

  const brand = el('div', { class: 'brand' }, el('div', { class: 'brand-mark' }, 'M'),
    el('div', { class: 'brand-text' }, el('strong', {}, 'Meridian Group'), el('span', {}, 'Attendance administration')));

  if (!token) {
    replace(page, el('div', { class: 'card login-card' }, brand, el('h1', {}, 'Invalid reset link'),
      el('p', { class: 'muted' }, 'This link is missing its token. Request a new password reset from the sign-in page.'),
      el('div', { class: 'form-actions' }, button('Go to sign in', { onClick: () => navigate('/login') }))));
    return () => {};
  }

  const pw1 = passwordInput({ autocomplete: 'new-password', 'data-testid': 'reset-password' });
  const pw2 = passwordInput({ autocomplete: 'new-password', 'data-testid': 'reset-password-confirm' });
  const err = formError('');
  const submit = button('Set new password', { type: 'submit' });
  const form = el('form', { class: 'stack', novalidate: true },
    field('New password', pw1.wrap, { hint: 'At least 10 characters; not your email or employee number; not a common password.' }),
    field('Confirm new password', pw2.wrap), err,
    el('div', { class: 'form-actions' }, submit));
  replace(page, el('div', { class: 'card login-card' }, brand, el('h1', {}, 'Choose a new password'), form));
  pw1.input.focus();

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(err, '');
    if (pw1.input.value.length < 10) { showFormError(err, 'The password must be at least 10 characters.'); return; }
    if (pw1.input.value !== pw2.input.value) { showFormError(err, 'The passwords do not match.'); return; }
    setBusy(submit, true, 'Saving…');
    try {
      await api.post('/auth/password/reset', { token, new_password: pw1.input.value });
      toast('Password updated. Please sign in with your new password.', 'success', 6000);
      navigate('/login');
    } catch (ex) {
      showFormError(err, ex.status === 400 || ex.status === 404 ? 'This reset link is invalid or has expired. Request a new one.' : ex);
    } finally { setBusy(submit, false, 'Saving…'); }
  });
  return () => {};
}
