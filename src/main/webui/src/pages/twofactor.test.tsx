import { beforeAll, expect, test, vi } from 'vitest';
import type { Factors } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

vi.mock('../webauthn.ts', () => ({
  passkeyAttestation: vi.fn(async () => ({ id: 'cred' })),
  passkeysSupported: () => true,
}));

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: TwoFactor } = await import('./TwoFactor.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/two-factor']}><TwoFactor /></t.router.MemoryRouter>);
});

const ME = { id: 4, login: 'carol', displayName: 'Carol', email: 'c@example.com', role: 'author', enrollRequired: false };
const NONE: Factors = { totp: false, passkeys: [], required: false };
const KEY = { id: 7, name: 'Laptop', createdAt: '2026-11-01T10:00:00Z', lastUsedAt: null };
const base = (me = ME) => ({ 'GET /session': { body: me }, 'GET /site': { body: { importAllowed: false } } });

test('enrollTotp', async () => {
  let factors = NONE;
  const calls = fakeApi({
    ...base(),
    'GET /2fa': () => ({ body: factors }),
    'POST /2fa/totp': { body: { secret: 'JBSWY3DP', uri: 'otpauth://x', qrSvg: '<svg xmlns="http://www.w3.org/2000/svg"/>' } },
    'POST /2fa/totp/confirm': () => { factors = { ...NONE, totp: true }; return { status: 204 }; },
  });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Set up' }));
  const img = (await view.findByAltText('QR code for your authenticator app')) as HTMLImageElement;
  expect(img.getAttribute('src')).toBe('data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg"/>'));
  expect(view.getByText('JBSWY3DP').tagName).toBe('CODE');
  await t.user.type(view.getByLabelText('6-digit code'), '123456');
  await t.user.click(view.getByRole('button', { name: 'Confirm' }));
  expect(calls.find((c) => c.path === '/2fa/totp/confirm')?.body).toEqual({ code: '123456' });
  await view.findByText('Add a second method in case you lose this one.');
  expect(view.getByRole('button', { name: 'Remove' })).toBeTruthy();
});

test('lastFactorMessage', async () => {
  fakeApi({
    ...base(), 'GET /2fa': { body: { ...NONE, passkeys: [KEY] } },
    'DELETE /2fa/passkeys/7': { status: 409, body: { error: 'last_factor' } },
  });
  vi.stubGlobal('confirm', () => true);
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Remove Laptop' }));
  expect((await view.findByRole('alert')).textContent)
    .toBe("This site requires two-factor authentication, so you can't remove your last method.");
});

test('bannerWhenRequired', async () => {
  fakeApi({ ...base({ ...ME, enrollRequired: true }), 'GET /2fa': { body: { ...NONE, required: true } } });
  const view = page();
  await view.findByText('This site requires two-factor authentication. Add a passkey or an authenticator app to continue.');
});

test('suggestsSecondFactor', async () => {
  fakeApi({ ...base(), 'GET /2fa': { body: { ...NONE, passkeys: [KEY] } } });
  const view = page();
  await view.findByText('Add a second method in case you lose this one.');
  expect(view.getByText(/works only on the address it was added on \(/)).toBeTruthy();
});

test('adds a passkey and offers Continue once enrollment is met', async () => {
  let enroll = true;
  let factors = NONE;
  const calls = fakeApi({
    ...base(), 'GET /session': () => ({ body: { ...ME, enrollRequired: enroll } }),
    'GET /2fa': () => ({ body: factors }),
    'POST /2fa/passkeys': () => { enroll = false; factors = { ...NONE, passkeys: [KEY] }; return { status: 201, body: KEY }; },
  });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Add a passkey' }));
  await view.findByRole('link', { name: 'Continue' });
  expect(calls.find((c) => c.path === '/2fa/passkeys')?.body).toEqual({ name: expect.any(String), credential: { id: 'cred' } });
});
