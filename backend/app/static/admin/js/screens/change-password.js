// Forced password change (must_change_password) and voluntary change.

import { api } from '../api.js';
import { session } from '../session.js';
import { el, replace, button, field, formError, showFormError, setBusy, toast } from '../ui.js';
import { passwordInput } from './login.js';

export function render(container, { setTitle, navigate, user, logout }) {
  const forced = Boolean(user?.must_change_password);
  setTitle('Change password', forced ? 'A new password is required before you continue' : 'Update your password');

  const current = passwordInput({ autocomplete: 'current-password', 'data-testid': 'cp-current' });
  const pw1 = passwordInput({ autocomplete: 'new-password', 'data-testid': 'cp-new' });
  const pw2 = passwordInput({ autocomplete: 'new-password', 'data-testid': 'cp-confirm' });
  const err = formError('');
  const submit = button('Change password', { type: 'submit', attrs: { 'data-testid': 'cp-submit' } });
  const form = el('form', { class: 'stack', novalidate: true },
    forced ? el('div', { class: 'form-warn' }, 'You signed in with a temporary password. Choose a new one to continue to the dashboard.') : null,
    field('Current password', current.wrap),
    field('New password', pw1.wrap, { hint: 'At least 10 characters; not your email or employee number; not a common password.' }),
    field('Confirm new password', pw2.wrap),
    err,
    el('div', { class: 'form-actions' },
      forced ? button('Sign out', { kind: 'secondary', onClick: logout }) : button('Cancel', { kind: 'secondary', onClick: () => navigate('/overview') }),
      submit));
  replace(container, el('div', { class: 'center-page' }, el('div', { class: 'card' }, el('h2', { style: { marginBottom: '12px' } }, 'Change password'), form)));
  current.input.focus();

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(err, '');
    if (!current.input.value) { showFormError(err, 'Enter your current password.'); return; }
    if (pw1.input.value.length < 10) { showFormError(err, 'The new password must be at least 10 characters.'); return; }
    if (pw1.input.value === current.input.value) { showFormError(err, 'The new password must differ from the current one.'); return; }
    if (pw1.input.value !== pw2.input.value) { showFormError(err, 'The passwords do not match.'); return; }
    setBusy(submit, true, 'Saving…');
    try {
      await api.post('/auth/password/change', { current_password: current.input.value, new_password: pw1.input.value });
      let me = null;
      try { me = await api.get('/auth/me'); } catch (x) { /* fall back to local flag */ }
      session.setUser({ ...(me?.user || session.user()), must_change_password: false });
      toast('Password changed.', 'success');
      navigate('/overview');
    } catch (ex) {
      showFormError(err, ex);
    } finally { setBusy(submit, false, 'Saving…'); }
  });
  return () => {};
}
