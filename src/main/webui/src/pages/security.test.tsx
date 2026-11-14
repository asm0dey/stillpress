import { beforeAll, expect, test } from 'vitest';
import type { MailSettings } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Security } = await import('./Security.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/security']}><Security /></t.router.MemoryRouter>);
});

const MAIL: MailSettings = {
  host: 'smtp.example.org', port: 587, security: 'starttls', username: 'blog', from: 'blog@example.org',
  passwordSet: true, lastError: null,
};
const SESSION = { 'GET /session': { body: { role: 'administrator' } }, 'GET /site': { body: { importAllowed: false } } };

test('rendersSavedPasswordPlaceholder', async () => {
  fakeApi({ ...SESSION, 'GET /mail': { body: MAIL } });
  const view = page();
  const password = (await view.findByLabelText('Password')) as HTMLInputElement;
  expect(password.placeholder).toBe('Saved');
  expect(password.value).toBe('');
  expect((view.getByLabelText('SMTP host') as HTMLInputElement).value).toBe('smtp.example.org');
});

test('an untouched password is kept on save', async () => {
  const calls = fakeApi({ ...SESSION, 'GET /mail': { body: MAIL }, 'PUT /mail': { body: MAIL } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Save' }));
  await view.findByText('Saved.');
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({
    host: 'smtp.example.org', port: 587, security: 'starttls', username: 'blog', from: 'blog@example.org', password: null,
  });
});

test('shows the last sending failure', async () => {
  fakeApi({ ...SESSION, 'GET /mail': { body: { ...MAIL, lastError: '2026-11-14T10:00:00Z Connection refused' } } });
  expect((await page().findByRole('alert')).textContent).toContain('2026-11-14T10:00:00Z Connection refused');
});

test('testButtonShowsFailureMessage', async () => {
  fakeApi({
    ...SESSION, 'GET /mail': { body: MAIL },
    'POST /mail/test': { status: 502, body: { error: 'mail_failed', message: 'Connection refused' } },
  });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Send test email' }));
  expect((await view.findByRole('alert')).textContent).toBe('The test email could not be sent: Connection refused');
});
