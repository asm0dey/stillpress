import { beforeAll, expect, test } from 'vitest';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: ForgotPassword } = await import('./ForgotPassword.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/forgot-password']}><ForgotPassword /></t.router.MemoryRouter>);
});

test('showsAskAdminWhenMailIsOff', async () => {
  fakeApi({ 'GET /login-options': { body: { mail: false, captcha: 'none' } } });
  const view = page();
  expect(await view.findByText("Email isn't set up on this site. Ask an administrator for a link to set your password.")).toBeTruthy();
  expect(view.queryByRole('textbox')).toBeNull();
});

test('showsSentMessageAfterSubmit', async () => {
  const calls = fakeApi({ 'GET /login-options': { body: { mail: true, captcha: 'none' } }, 'POST /password-reset': { status: 204 } });
  const view = page();
  await t.user.type(await view.findByLabelText('Login or email'), 'ada@example.com');
  await t.user.click(view.getByRole('button', { name: 'Send link' }));
  expect((await view.findByRole('status')).textContent)
    .toBe('If an account matches, we sent a link to its email address. The link works for one hour.');
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ login: 'ada@example.com', website: '' });
});

test('too many requests says to wait', async () => {
  fakeApi({ 'GET /login-options': { body: { mail: true, captcha: 'none' } }, 'POST /password-reset': { status: 429, body: { error: 'rate_limited' } } });
  const view = page();
  await t.user.type(await view.findByLabelText('Login or email'), 'ada');
  await t.user.click(view.getByRole('button', { name: 'Send link' }));
  expect((await view.findByRole('alert')).textContent).toBe('Too many attempts. Wait a few minutes and try again.');
});
