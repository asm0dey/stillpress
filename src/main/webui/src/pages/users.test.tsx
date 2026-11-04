/// <reference types="bun" />
import { afterEach, beforeAll, expect, test } from 'bun:test';
import type { UserView } from '../api.ts';
import { useDom } from '../editor/testdom.ts';
import { fakeApi } from '../testapi.ts';

useDom();

// React and Testing Library look for the DOM when they load, so they come in after useDom; the pure entry registers no
// hooks of its own (bun refuses hooks registered inside beforeAll)
let rtl: typeof import('@testing-library/react/pure');
let user: typeof import('@testing-library/user-event').userEvent;
let page: () => ReturnType<typeof rtl.render>;
const realFetch = globalThis.fetch;
beforeAll(async () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  rtl = await import('@testing-library/react/pure');
  ({ userEvent: user } = await import('@testing-library/user-event'));
  const { MemoryRouter } = await import('react-router');
  const { default: Users } = await import('./Users.tsx');
  page = () => rtl.render(<MemoryRouter initialEntries={['/users']}><Users /></MemoryRouter>);
});
afterEach(() => {
  rtl.cleanup();
  globalThis.fetch = realFetch;
});

const ADA: UserView = { id: 1, login: 'ada', displayName: 'Ada', email: 'ada@example.com', role: 'administrator', hasPassword: true };
const BOB: UserView = { id: 2, login: 'bob', displayName: 'Bob', email: 'bob@example.com', role: 'author', hasPassword: false };
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
  await user.selectOptions(await view.findByLabelText('Role of bob'), 'editor');
  await rtl.waitFor(() => expect(calls.filter((c) => c.path === '/users')).toHaveLength(2));
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ role: 'editor' });
});

test('the last administrator keeps the role', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [ADA] }, 'PUT /users/1/role': { status: 409, body: { error: 'last_administrator' } } });
  const view = page();
  await user.selectOptions(await view.findByLabelText('Role of ada'), 'author');
  expect((await view.findByRole('alert')).textContent).toBe('There must be at least one administrator with a password.');
});

test('a profile edit with a taken email marks the email field', async () => {
  const calls = fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'PUT /users/2': { status: 409, body: { error: 'email_taken' } } });
  const view = page();
  await user.click(await view.findByRole('button', { name: 'Edit' }));
  const email = view.getByLabelText('Email', { selector: '[name="edit-email"]' });
  await user.clear(email);
  await user.type(email, 'ada@example.com');
  await user.click(view.getByRole('button', { name: 'Save' }));
  expect((await view.findByRole('alert')).textContent).toBe('Another user has this email.');
  expect(email.getAttribute('aria-invalid')).toBe('true');
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ displayName: 'Bob', email: 'ada@example.com' });
});

test('a saved profile closes the form', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'PUT /users/2': { status: 204 } });
  const view = page();
  await user.click(await view.findByRole('button', { name: 'Edit' }));
  await user.click(view.getByRole('button', { name: 'Save' }));
  expect(await view.findByRole('button', { name: 'Edit' })).toBeTruthy();
});

test('adding a user shows its set-password link and clears the form', async () => {
  const calls = fakeApi({
    ...SESSION, 'GET /users': { body: [ADA] },
    'POST /users': { status: 201, body: { setPasswordUrl: '/admin/set-password?token=t' } },
  });
  const view = page();
  await user.type(view.getByLabelText('Login', { selector: '[name="login"]' }), 'carol');
  await user.type(view.getByLabelText('Email', { selector: '[name="email"]' }), 'carol@example.com');
  await user.type(view.getByLabelText('Display name', { selector: '[name="displayName"]' }), 'Carol');
  await user.selectOptions(view.getByLabelText('Role'), 'editor');
  await user.click(view.getByRole('button', { name: 'Add user' }));
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
  await user.type(view.getByLabelText('Login', { selector: '[name="login"]' }), 'ada');
  await user.type(view.getByLabelText('Email', { selector: '[name="email"]' }), 'x@example.com');
  await user.type(view.getByLabelText('Display name', { selector: '[name="displayName"]' }), 'X');
  await user.click(view.getByRole('button', { name: 'Add user' }));
  expect((await view.findByRole('alert')).textContent).toBe('That login is already taken.');
});

test('copying a set-password link shows it', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'POST /users/2/password-link': { body: { url: '/admin/set-password?token=u' } } });
  const view = page();
  await user.click(await view.findByRole('button', { name: 'Copy set-password link' }));
  expect((await view.findByRole('status')).querySelector('code')?.textContent).toBe('http://localhost/admin/set-password?token=u');
});

test('an expired session token says to reload', async () => {
  fakeApi({ ...SESSION, 'GET /users': { body: [BOB] }, 'POST /users/2/password-link': { status: 400 } });
  const view = page();
  await user.click(await view.findByRole('button', { name: 'Copy set-password link' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});
