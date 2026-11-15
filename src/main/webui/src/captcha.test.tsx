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

/** Once the mount's effects have run, so the widget is listened to, the widget reports `state`. */
async function solve(view: ReturnType<typeof t.rtl.render>, state = 'verified') {
  await t.rtl.act(async () => {});
  await t.rtl.act(async () => {
    view.container.querySelector('altcha-widget')!
      .dispatchEvent(new CustomEvent('statechange', { detail: { state, payload: 'solved' } }));
  });
}

test('loginDisabledUntilToken', async () => {
  fakeApi({ 'GET /login-options': { body: opts('altcha') } });
  const view = page();
  const button = view.getByRole('button', { name: 'Log in' }) as HTMLButtonElement;
  expect(button.disabled).toBe(true);
  await t.rtl.waitFor(() => expect(view.container.querySelector('altcha-widget')).toBeTruthy());
  await solve(view);
  expect(button.disabled).toBe(false);
  await solve(view, 'expired');
  expect(button.disabled).toBe(true);
});

test('captchaFailedRemountsTheWidget', async () => {
  const calls = fakeApi({
    'GET /login-options': { body: opts('altcha') },
    'POST /login': { status: 400, body: { error: 'captcha_failed' } },
  });
  const view = page();
  const button = view.getByRole('button', { name: 'Log in' }) as HTMLButtonElement;
  await t.rtl.waitFor(() => expect(view.container.querySelector('altcha-widget')).toBeTruthy());
  await solve(view);
  await t.user.type(view.getByLabelText('Login or email'), 'ada');
  await t.user.type(view.getByLabelText('Password'), 'secret');
  await t.user.click(button);
  expect(await view.findByText("The check didn't pass. Try again.")).toBeTruthy();
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ login: 'ada', password: 'secret', captcha: 'solved', website: '' });
  expect(button.disabled).toBe(true);
});

test('tokenKeptOnRateLimit', async () => {
  const calls = fakeApi({
    'GET /login-options': { body: opts('altcha') },
    'POST /login': { status: 429, body: { error: 'rate_limited' } },
  });
  const view = page();
  const button = view.getByRole('button', { name: 'Log in' }) as HTMLButtonElement;
  await t.rtl.waitFor(() => expect(view.container.querySelector('altcha-widget')).toBeTruthy());
  const widget = view.container.querySelector('altcha-widget');
  await solve(view);
  await t.user.type(view.getByLabelText('Login or email'), 'ada');
  await t.user.type(view.getByLabelText('Password'), 'secret');
  await t.user.click(button);
  await view.findByText('Too many attempts. Wait a few minutes and try again.');
  expect(button.disabled).toBe(false);
  expect(view.container.querySelector('altcha-widget')).toBe(widget);
  await t.user.click(button);
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => (c.body as { captcha: string }).captcha))
    .toEqual(['solved', 'solved']));
});

test('turnstileLoadFailureIsShown', async () => {
  fakeApi({ 'GET /login-options': { body: opts('turnstile') } });
  const view = page();
  const button = view.getByRole('button', { name: 'Log in' }) as HTMLButtonElement;
  // the widget mounts once the options arrive; the script may be an earlier test's, so fail whichever is pending
  await t.rtl.waitFor(() => {
    t.rtl.act(() => { document.querySelectorAll('script[src*="turnstile"]').forEach((el) => el.dispatchEvent(new Event('error'))); });
    expect(view.getByRole('alert').textContent)
      .toBe('Could not load the CAPTCHA. Check your connection and reload the page.');
  });
  expect(button.disabled).toBe(true);
});

test('altchaFollowsTheAdminLanguage', async () => {
  const { loadLocale } = await import('./i18n/index.ts');
  await loadLocale('ru');
  try {
    const view = t.rtl.render(<C.Captcha options={opts('altcha')} onToken={() => {}} />);
    expect((view.container.querySelector('altcha-widget') as HTMLElement & { language: string }).language).toBe('ru');
  } finally {
    await loadLocale('en');
  }
});

test('challengeFetchKeepsQueryAndInit', async () => {
  const calls = fakeApi({ 'GET /altcha/challenge?x=1': { body: {} } });
  const view = t.rtl.render(<C.Captcha options={opts('altcha')} onToken={() => {}} />);
  const widget = view.container.querySelector('altcha-widget')!;
  let config: { fetch?: typeof fetch } = {};
  Object.defineProperty(widget, 'configure', { value: async (c: typeof config) => { config = c; } });
  widget.dispatchEvent(new Event('load'));
  const signal = new AbortController().signal;
  await config.fetch!('http://localhost/admin/api/altcha/challenge?x=1', { method: 'GET', signal });
  expect(calls).toEqual([{ method: 'GET', path: '/altcha/challenge?x=1', body: undefined }]);
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
