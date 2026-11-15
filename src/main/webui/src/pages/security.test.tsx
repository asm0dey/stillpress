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
const CAPTCHA = { provider: 'none', turnstileSiteKey: null, secretSet: false, locked: false };
const SESSION = {
  'GET /session': { body: { role: 'administrator' } }, 'GET /site': { body: { importAllowed: false } }, 'GET /captcha': { body: CAPTCHA },
};

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
  await t.user.click((await view.findAllByRole('button', { name: 'Save' }))[0]);
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

test('lockedCaptchaDisablesTheFields', async () => {
  fakeApi({ ...SESSION, 'GET /mail': { body: MAIL }, 'GET /captcha': { body: { ...CAPTCHA, provider: 'altcha', locked: true } } });
  const view = page();
  expect(await view.findByText('Set by STILLPRESS_CAPTCHA_PROVIDER on the server.')).toBeTruthy();
  expect((view.getByLabelText('ALTCHA (self-hosted)') as HTMLInputElement).checked).toBe(true);
  expect(view.getByLabelText('ALTCHA (self-hosted)').matches(':disabled')).toBe(true);
});

test('turnstileSavesKeysAndShowsRejectedSecret', async () => {
  const calls = fakeApi({
    ...SESSION, 'GET /mail': { body: MAIL }, 'GET /captcha': { body: CAPTCHA },
    'PUT /captcha': { status: 400, body: { error: 'invalid_turnstile_secret' } },
  });
  const view = page();
  await t.user.click(await view.findByLabelText('Cloudflare Turnstile'));
  await t.user.type(view.getByLabelText('Site key'), '0x4AAA');
  await t.user.type(view.getByLabelText('Secret key'), 'bad');
  await t.user.click(view.getAllByRole('button', { name: 'Save' })[1]);
  expect((await view.findByRole('alert')).textContent).toBe('Cloudflare rejected this secret key.');
  expect(calls.find((c) => c.path === '/captcha' && c.method === 'PUT')?.body)
    .toEqual({ provider: 'turnstile', turnstileSiteKey: '0x4AAA', turnstileSecret: 'bad' });
});
