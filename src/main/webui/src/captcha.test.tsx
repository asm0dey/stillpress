import { beforeAll, expect, test } from 'vitest';
import type { LoginOptions } from './Captcha.tsx';
import { fakeApi, usePages } from './testapi.ts';

const t = usePages();
let C: typeof import('./Captcha.tsx');
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  C = await import('./Captcha.tsx');
  const { default: Login } = await import('./pages/Login.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/login']}><Login /></t.router.MemoryRouter>);
});

const opts = (captcha: LoginOptions['captcha']): LoginOptions => ({ mail: true, captcha, turnstileSiteKey: 'site-key' });

test('noneRendersNothing', () => {
  const view = t.rtl.render(<C.Captcha options={opts('none')} onToken={() => {}} />);
  expect(view.container.innerHTML).toBe('');
});

test('altchaVerifiedEventYieldsToken', () => {
  const tokens: (string | undefined)[] = [];
  const view = t.rtl.render(<C.Captcha options={opts('altcha')} onToken={(tok) => tokens.push(tok)} />);
  const widget = view.container.querySelector('altcha-widget')!;
  expect((widget as HTMLElementTagNameMap['altcha-widget']).challenge).toBe('/admin/api/altcha/challenge');
  t.rtl.act(() => {
    widget.dispatchEvent(new CustomEvent('statechange', { detail: { state: 'verified', payload: 'solved' } }));
    widget.dispatchEvent(new CustomEvent('statechange', { detail: { state: 'expired' } }));
  });
  expect(tokens).toEqual(['solved', undefined]);
});

test('turnstileScriptLoadsOnce', () => {
  t.rtl.render(<C.Captcha options={opts('turnstile')} onToken={() => {}} />);
  t.rtl.render(<C.Captcha options={opts('turnstile')} onToken={() => {}} />);
  expect([...document.querySelectorAll('script')].map((s) => s.src))
    .toEqual(['https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit']);
});

test('loginDisabledUntilToken', async () => {
  const calls = fakeApi({
    'GET /login-options': { body: opts('altcha') },
    'POST /login': { status: 400, body: { error: 'captcha_failed' } },
  });
  const view = page();
  const button = view.getByRole('button', { name: 'Log in' }) as HTMLButtonElement;
  expect(button.disabled).toBe(true);
  // retried until the widget is mounted and listened to, which follows the options' arrival
  await t.rtl.waitFor(() => {
    view.container.querySelector('altcha-widget')!
      .dispatchEvent(new CustomEvent('statechange', { detail: { state: 'verified', payload: 'solved' } }));
    expect(button.disabled).toBe(false);
  });
  await t.user.type(view.getByLabelText('Login or email'), 'ada');
  await t.user.type(view.getByLabelText('Password'), 'secret');
  await t.user.click(button);
  expect(await view.findByText("The check didn't pass. Try again.")).toBeTruthy();
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ login: 'ada', password: 'secret', captcha: 'solved', website: '' });
  // the widget is fresh, so a new token is needed
  expect(button.disabled).toBe(true);
});

test('altchaFetchesChallengeThroughAdminFetch', async () => {
  // the widget's own fetch would leave the CSRF token the answer carries unkept, so the form's POST is refused
  const calls = fakeApi({ 'GET /altcha/challenge': { body: { algorithm: 'SHA-256' } } });
  const view = t.rtl.render(<C.Captcha options={opts('altcha')} onToken={() => {}} />);
  const widget = view.container.querySelector('altcha-widget')!;
  let config: { fetch?: typeof fetch } = {};
  Object.defineProperty(widget, 'configure', { value: async (c: typeof config) => { config = c; } });
  widget.dispatchEvent(new Event('load'));
  await config.fetch!('http://localhost/admin/api/altcha/challenge');
  expect(calls).toEqual([{ method: 'GET', path: '/altcha/challenge', body: undefined }]);
});
