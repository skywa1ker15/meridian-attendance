// Broadcast notification to all employees or one department (admin only).

import { api } from '../api.js';
import { el, replace, pageHead, button, card, field, input, textarea, formError, showFormError, setBusy, toast, confirmDialog } from '../ui.js';
import { departmentSelect, cleanQuery } from '../data.js';

export function render(container, { setTitle }) {
  setTitle('Broadcast notification', 'In-app inbox and push (when FCM is configured)');
  const title = input({ maxlength: 160, required: true, 'data-testid': 'bc-title' });
  const body = textarea({ maxlength: 2000, rows: 5, required: true, 'data-testid': 'bc-body' });
  const count = el('span', { class: 'hint' }, '0 / 2000');
  body.addEventListener('input', () => { count.textContent = `${body.value.length} / 2000`; });
  const deptHost = el('div', { class: 'field' });
  let deptSel = null;
  departmentSelect({}, '', { allLabel: 'All active employees' }).then((s) => { deptSel = s; replace(deptHost, field('Audience', s)); });
  const err = formError('');
  const submit = button('Send broadcast', { type: 'submit', icon: 'send', attrs: { 'data-testid': 'bc-send' } });
  const result = el('div');
  const form = el('form', { class: 'stack', novalidate: true },
    field('Title', title), el('div', { class: 'field' }, el('label', { for: body.id = 'bc-body' }, 'Message'), body, count), deptHost, err,
    el('div', { class: 'form-actions' }, submit));
  replace(container, pageHead('Broadcast notification', 'Messages are stored in each recipient\'s inbox and are audit-logged.'), card('Compose', form), result);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(err, '');
    const payload = cleanQuery({ title: title.value.trim(), body: body.value.trim(), department_id: deptSel ? deptSel.value : '' });
    if (!payload.title || !payload.body) { showFormError(err, 'Title and message are required.'); return; }
    const audience = deptSel && deptSel.value ? deptSel.options[deptSel.selectedIndex].text : 'all active employees';
    const ok = await confirmDialog({ title: 'Send broadcast', confirmLabel: 'Send', message: `Send "${payload.title}" to ${audience}? This cannot be recalled.` });
    if (!ok) return;
    setBusy(submit, true, 'Sending…');
    try {
      const res = await api.post('/admin/notifications/broadcast', payload);
      toast(`Broadcast sent to ${res.sent} recipient${res.sent === 1 ? '' : 's'}.`, 'success');
      replace(result, el('div', { class: 'form-info', 'data-testid': 'bc-result' }, `Last broadcast "${payload.title}" delivered to ${res.sent} inbox${res.sent === 1 ? '' : 'es'}.`));
      title.value = ''; body.value = ''; count.textContent = '0 / 2000';
    } catch (ex) { showFormError(err, ex); } finally { setBusy(submit, false, 'Sending…'); }
  });
  return () => {};
}
