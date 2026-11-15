import { beforeAll, expect, test } from 'vitest';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Setup } = await import('./Setup.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = () => t.rtl.render(
    <MemoryRouter initialEntries={['/setup']}>
      <Routes>
        <Route path="/setup" element={<Setup />} />
        <Route path="/" element={<p>dashboard</p>} />
        <Route path="/import" element={<p>import page</p>} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    </MemoryRouter>,
  );
});

const OPTIONS = { 'GET /login-options': { body: { mail: false, captcha: 'none' } } };

async function submit(view: ReturnType<typeof page>) {
  await t.user.type(await view.findByLabelText('Setup token'), 'tok');
  await t.user.type(view.getByLabelText('Site title'), 'My blog');
  await t.user.type(view.getByLabelText('Login'), 'ada');
  await t.user.type(view.getByLabelText('Email'), 'ada@example.com');
  await t.user.type(view.getByLabelText('Display name'), 'Ada');
  await t.user.type(view.getByLabelText('Password'), 'a long enough password');
  const button = view.getByRole('button', { name: 'Create site' });
  await t.rtl.waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  await t.user.click(button);
}

async function fail(reply: { status: number; body?: unknown }) {
  fakeApi({ ...OPTIONS, 'POST /setup': reply });
  const view = page();
  await submit(view);
  return view;
}

test('setting up sends the form and offers how to begin', async () => {
  const calls = fakeApi({ ...OPTIONS, 'POST /setup': { status: 204 } });
  const view = page();
  await submit(view);
  expect(await view.findByRole('heading', { name: 'Your site is ready' })).toBeTruthy();
  expect(calls.find((c) => c.path === '/setup' && c.method === 'POST')?.body).toEqual({
    token: 'tok', title: 'My blog', language: 'en', login: 'ada', email: 'ada@example.com', displayName: 'Ada',
    password: 'a long enough password', website: '', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  });
});

test('starting empty goes to the dashboard', async () => {
  const calls = fakeApi({ ...OPTIONS, 'POST /setup': { status: 204 }, 'POST /setup/start-empty': { status: 204 } });
  const view = page();
  await submit(view);
  await t.user.click(await view.findByRole('button', { name: /Start empty/ }));
  expect(await view.findByText('dashboard')).toBeTruthy();
  expect(calls.some((c) => c.method === 'POST' && c.path === '/setup/start-empty')).toBe(true);
});

test('a failed empty start says so', async () => {
  fakeApi({ ...OPTIONS, 'POST /setup': { status: 204 }, 'POST /setup/start-empty': { status: 500 } });
  const view = page();
  await submit(view);
  await t.user.click(await view.findByRole('button', { name: /Start empty/ }));
  expect((await view.findByRole('alert')).textContent).toBe('Could not start an empty site. Try again.');
});

test('an expired session token on the empty start says to reload', async () => {
  fakeApi({ ...OPTIONS, 'POST /setup': { status: 204 }, 'POST /setup/start-empty': { status: 400 } });
  const view = page();
  await submit(view);
  await t.user.click(await view.findByRole('button', { name: /Start empty/ }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});

test('importing goes to the import page', async () => {
  fakeApi({ ...OPTIONS, 'POST /setup': { status: 204 } });
  const view = page();
  await submit(view);
  await t.user.click(await view.findByRole('button', { name: /Import from WordPress/ }));
  expect(await view.findByText('import page')).toBeTruthy();
});

test('a wrong token marks the token field', async () => {
  const view = await fail({ status: 403, body: { error: 'forbidden' } });
  expect((await view.findByRole('alert')).textContent).toBe('That token does not match the one in the server log.');
  expect(view.getByLabelText('Setup token').getAttribute('aria-invalid')).toBe('true');
});

test('an invalid field gets its message', async () => {
  const view = await fail({ status: 400, body: { error: 'invalid', field: 'language' } });
  expect((await view.findByRole('alert')).textContent).toBe('Use a language code such as en or en-GB.');
  expect(view.getByLabelText('Language').getAttribute('aria-invalid')).toBe('true');
});

test('a taken email marks the email field', async () => {
  const view = await fail({ status: 409, body: { error: 'email_taken' } });
  expect((await view.findByRole('alert')).textContent).toBe('Another user has this email.');
  expect(view.getByLabelText('Email').getAttribute('aria-invalid')).toBe('true');
});

test('a site already set up links to the login page', async () => {
  const view = await fail({ status: 409, body: { error: 'already_set_up' } });
  expect((await view.findByRole('alert')).textContent).toBe('This site is already set up. Log in instead');
  await t.user.click(view.getByRole('link', { name: 'Log in instead' }));
  expect(await view.findByText('login page')).toBeTruthy();
});

test('a failed CAPTCHA says so', async () => {
  const view = await fail({ status: 403, body: { error: 'captcha_failed' } });
  expect((await view.findByRole('alert')).textContent).toBe("The check didn't pass. Try again.");
});

test('an expired session token says to reload', async () => {
  const view = await fail({ status: 400 });
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});

test('any other failure points at the log', async () => {
  const view = await fail({ status: 500 });
  expect((await view.findByRole('alert')).textContent).toBe('Setup failed. Check the server log and try again.');
});
