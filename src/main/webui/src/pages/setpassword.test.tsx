import { beforeAll, expect, test } from 'vitest';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: SetPassword } = await import('./SetPassword.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/set-password?token=abc']}><SetPassword /></t.router.MemoryRouter>);
});

test('too many requests says to wait', async () => {
  fakeApi({ 'GET /login-options': { body: { mail: false, captcha: 'none' } }, 'POST /set-password': { status: 429, body: { error: 'rate_limited' } } });
  const view = page();
  await t.user.type(await view.findByLabelText('New password'), 'a long enough password');
  await t.user.type(view.getByLabelText('Repeat password'), 'a long enough password');
  await t.user.click(view.getByRole('button', { name: 'Set password' }));
  expect((await view.findByRole('alert')).textContent).toBe('Too many attempts. Wait a few minutes and try again.');
});

test('enrollAfterSetPassword', async () => {
  fakeApi({ 'GET /login-options': { body: { mail: false, captcha: 'none' } }, 'POST /set-password': { body: { enroll: true } } });
  const { MemoryRouter, Route, Routes } = t.router;
  const { default: SetPassword } = await import('./SetPassword.tsx');
  const view = t.rtl.render(<MemoryRouter initialEntries={['/set-password?token=abc']}>
    <Routes><Route path="/set-password" element={<SetPassword />} /><Route path="/two-factor" element={<p>enroll page</p>} /></Routes></MemoryRouter>);
  await t.user.type(await view.findByLabelText('New password'), 'a long enough password');
  await t.user.type(view.getByLabelText('Repeat password'), 'a long enough password');
  await t.user.click(view.getByRole('button', { name: 'Set password' }));
  expect(await view.findByText('enroll page')).toBeTruthy();
});
