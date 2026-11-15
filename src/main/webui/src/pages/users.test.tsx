import { beforeAll, expect, test } from 'vitest';
import type { UserView } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Users } = await import('./Users.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/users']}><Users /></t.router.MemoryRouter>);
});

const ADA: UserView = { id: 1, login: 'ada', displayName: 'Ada', email: 'ada@example.com', role: 'administrator', hasPassword: true, twoFactor: false };
const BOB: UserView = { id: 2, login: 'bob', displayName: 'Bob', email: 'bob@example.com', role: 'author', hasPassword: false, twoFactor: false };
const SESSION = { 'GET /session': { body: { role: 'administrator' } }, 'GET /site': { body: { importAllowed: false } } };

test('lists the users', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [ADA, BOB] } });
  const view = page();
  expect(await view.findByText('bob@example.com')).toBeTruthy();
  expect((view.getByLabelText('Role of bob') as HTMLSelectElement).value).toBe('author');
});

test('says so when the users cannot be loaded', async () => {
  fakeApi({ ...SESSION, 'GET /users': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the users.');
});

test('changing a role sends it and reloads the list', async () => {
  const calls = fakeApi({ ...SESSION, 'GET /users': { body: [ADA, BOB] }, 'PUT /users/2/role': { status: 204 } });
  const view = page();
  await t.user.selectOptions(await view.findByLabelText('Role of bob'), 'editor');
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.path === '/users')).toHaveLength(2));
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ role: 'editor' });
});

test('the last administrator keeps the role', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [ADA] }, 'PUT /users/1/role': { status: 409, body: { error: 'last_administrator' } } });
  const view = page();
  await t.user.selectOptions(await view.findByLabelText('Role of ada'), 'author');
  expect((await view.findByRole('alert')).textContent).toBe('There must be at least one administrator with a password.');
});

test('a profile edit with a taken email marks the email field', async () => {
  const calls = fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'PUT /users/2': { status: 409, body: { error: 'email_taken' } } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Edit' }));
  const email = view.getByLabelText('Email', { selector: '[name="edit-email"]' });
  await t.user.clear(email);
  await t.user.type(email, 'ada@example.com');
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  expect((await view.findByRole('alert')).textContent).toBe('Another user has this email.');
  expect(email.getAttribute('aria-invalid')).toBe('true');
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ displayName: 'Bob', email: 'ada@example.com' });
});

test('a saved profile closes the form', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'PUT /users/2': { status: 204 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Edit' }));
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  expect(await view.findByRole('button', { name: 'Edit' })).toBeTruthy();
});

test('adding a user shows its set-password link and clears the form', async () => {
  const calls = fakeApi({
    ...SESSION, 'GET /users': { body: [ADA] },
    'POST /users': { status: 201, body: { setPasswordUrl: '/admin/set-password?token=t' } },
  });
  const view = page();
  await t.user.type(view.getByLabelText('Login', { selector: '[name="login"]' }), 'carol');
  await t.user.type(view.getByLabelText('Email', { selector: '[name="email"]' }), 'carol@example.com');
  await t.user.type(view.getByLabelText('Display name', { selector: '[name="displayName"]' }), 'Carol');
  await t.user.selectOptions(view.getByLabelText('Role'), 'editor');
  await t.user.click(view.getByRole('button', { name: 'Add user' }));
  const link = await view.findByRole('status');
  expect(link.textContent).toContain('Set-password link for carol');
  expect(link.querySelector('code')?.textContent).toBe('http://localhost/admin/set-password?token=t');
  expect(calls.find((c) => c.method === 'POST')?.body)
    .toEqual({ login: 'carol', email: 'carol@example.com', displayName: 'Carol', role: 'editor' });
  expect((view.getByLabelText('Login', { selector: '[name="login"]' }) as HTMLInputElement).value).toBe('');
});

test('a taken login marks the login field', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [ADA] }, 'POST /users': { status: 409, body: { error: 'login_taken' } } });
  const view = page();
  await t.user.type(view.getByLabelText('Login', { selector: '[name="login"]' }), 'ada');
  await t.user.type(view.getByLabelText('Email', { selector: '[name="email"]' }), 'x@example.com');
  await t.user.type(view.getByLabelText('Display name', { selector: '[name="displayName"]' }), 'X');
  await t.user.click(view.getByRole('button', { name: 'Add user' }));
  expect((await view.findByRole('alert')).textContent).toBe('That login is already taken.');
});

test('copying a set-password link shows it', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'POST /users/2/password-link': { body: { url: '/admin/set-password?token=u' } } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Copy set-password link' }));
  expect((await view.findByRole('status')).querySelector('code')?.textContent).toBe('http://localhost/admin/set-password?token=u');
});

test('an expired session token says to reload', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'POST /users/2/password-link': { status: 400 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Copy set-password link' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});

test('sendInviteButtonOnlyWithMail', async () => {
  // ADA has a password; BOB has none and an email; CAROL has neither
  const CAROL: UserView = { ...BOB, id: 3, login: 'carol', email: '' };
  fakeApi({ ...SESSION, 'GET /login-options': { body: { mail: false } }, 'GET /users': { body: [ADA, BOB, CAROL] } });
  let view = page();
  await view.findByText('bob@example.com');
  expect(view.queryByRole('button', { name: 'Send invite' })).toBeNull();
  view.unmount();

  const calls = fakeApi({
    ...SESSION, 'GET /login-options': { body: { mail: true } }, 'GET /users': { body: [ADA, BOB, CAROL] },
    'POST /users/2/invite': { status: 204 },
  });
  view = page();
  await t.user.click(await view.findByRole('button', { name: 'Send invite' }));
  expect(view.getAllByRole('button', { name: 'Send invite' })).toHaveLength(1);
  expect((await view.findByRole('status')).textContent).toBe('Invite queued for bob@example.com. If it does not arrive, check Security → Email.');
  expect(calls.filter((c) => c.method === 'POST').map((c) => c.path)).toEqual(['/users/2/invite']);
});

test('invite errors get their own messages', async () => {
  fakeApi({
    ...SESSION, 'GET /login-options': { body: { mail: true } }, 'GET /users': { body: [BOB] },
    'POST /users/2/invite': { status: 409, body: { error: 'mail_not_configured' } },
  });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Send invite' }));
  expect((await view.findByRole('alert')).textContent).toBe('Email is not set up. Configure it under Security → Email.');
});

test('adding a user with mail on says the invite went out', async () => {
  fakeApi({
    ...SESSION, 'GET /login-options': { body: { mail: true } }, 'GET /users': { body: [ADA] },
    'POST /users': { status: 201, body: { id: 5, mailed: true } },
  });
  const view = page();
  await t.user.type(view.getByLabelText('Login', { selector: '[name="login"]' }), 'carol');
  await t.user.type(view.getByLabelText('Email', { selector: '[name="email"]' }), 'carol@example.com');
  await t.user.type(view.getByLabelText('Display name', { selector: '[name="displayName"]' }), 'Carol');
  await t.user.click(view.getByRole('button', { name: 'Add user' }));
  expect((await view.findByRole('status')).textContent).toBe('Invite queued for carol@example.com. If it does not arrive, check Security → Email.');
});
