import { beforeAll, beforeEach, expect, test, vi } from 'vitest';
import { fakeApi, usePages } from '../testapi.ts';

const assertion = { id: 'cred' };
vi.mock('../webauthn.ts', () => ({
  passkeysSupported: () => !!window.PublicKeyCredential,
  passkeyAssertion: vi.fn(async () => assertion),
}));

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Login } = await import('./Login.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = () => t.rtl.render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes><Route path="/login" element={<Login />} /><Route path="/" element={<p>home</p>} /></Routes>
    </MemoryRouter>,
  );
});
beforeEach(() => { (window as { PublicKeyCredential?: unknown }).PublicKeyCredential = class {}; });

const base = { 'GET /login-options': { body: { mail: false, captcha: 'none' } }, 'GET /session': { body: { locale: 'en' } } };
async function password(view: ReturnType<typeof page>) {
  await t.user.type(await view.findByLabelText('Login or email'), 'ann');
  await t.user.type(view.getByLabelText('Password'), 'secret');
  await t.user.click(view.getByRole('button', { name: 'Log in' }));
}

test('passwordThenCode', async () => {
  const calls = fakeApi({ ...base, 'POST /login': { body: { totp: true, passkey: false } }, 'POST /login/totp': { status: 204 } });
  const view = page();
  await password(view);
  const code = await view.findByLabelText('Code');
  expect(code.getAttribute('autocomplete')).toBe('one-time-code');
  expect(code.getAttribute('inputmode')).toBe('numeric');
  expect(view.queryByRole('button', { name: 'Use a passkey' })).toBeNull();
  await t.user.type(code, '123456');
  await t.user.click(view.getByRole('button', { name: 'Confirm' }));
  expect(await view.findByText('home')).toBeTruthy();
  expect(calls.find((c) => c.path === '/login/totp')?.body).toEqual({ code: '123456' });
});

test('wrongCodeIsNamed', async () => {
  fakeApi({ ...base, 'POST /login': { body: { totp: true, passkey: false } },
    'POST /login/totp': { status: 401, body: { error: 'invalid_code' } } });
  const view = page();
  await password(view);
  await t.user.type(await view.findByLabelText('Code'), '000000');
  await t.user.click(view.getByRole('button', { name: 'Confirm' }));
  expect((await view.findByRole('alert')).textContent).toBe('The code is incorrect.');
});

test('passkeyAsSecondStep', async () => {
  const calls = fakeApi({ ...base, 'POST /login': { body: { totp: false, passkey: true } }, 'POST /login/passkey-step': { status: 204 } });
  const view = page();
  await password(view);
  await t.user.click(await view.findByRole('button', { name: 'Use a passkey' }));
  expect(await view.findByText('home')).toBeTruthy();
  expect(calls.find((c) => c.path === '/login/passkey-step')?.body).toEqual(assertion);
});

test('expiredSecondStepReturnsToPassword', async () => {
  fakeApi({ ...base, 'POST /login': { body: { totp: true, passkey: false } },
    'POST /login/totp': { status: 401, body: { error: 'second_step_expired' } } });
  const view = page();
  await password(view);
  await t.user.type(await view.findByLabelText('Code'), '123456');
  await t.user.click(view.getByRole('button', { name: 'Confirm' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your sign-in timed out. Enter your password again.');
  expect(view.getByLabelText('Password')).toBeTruthy();
});

test('signInWithPasskey', async () => {
  const calls = fakeApi({ ...base, 'POST /login/passkey': { status: 204 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Sign in with passkey' }));
  expect(await view.findByText('home')).toBeTruthy();
  expect(calls.find((c) => c.path === '/login/passkey')?.body).toEqual(assertion);
});

test('passkeyButtonHiddenWithoutSupport', async () => {
  delete (window as { PublicKeyCredential?: unknown }).PublicKeyCredential;
  fakeApi(base);
  const view = page();
  await view.findByLabelText('Login or email');
  expect(view.queryByRole('button', { name: 'Sign in with passkey' })).toBeNull();
});

test('backFromTheSecondStepReturnsToPassword', async () => {
  fakeApi({ ...base, 'POST /login': { body: { totp: true, passkey: false } } });
  const view = page();
  await password(view);
  await t.user.type(await view.findByLabelText('Code'), '12');
  await t.user.click(view.getByRole('button', { name: 'Use a different account' }));
  expect((view.getByLabelText('Login or email') as HTMLInputElement).value).toBe('ann');
  expect((view.getByLabelText('Password') as HTMLInputElement).value).toBe('');
  expect(view.queryByLabelText('Code')).toBeNull();
});
