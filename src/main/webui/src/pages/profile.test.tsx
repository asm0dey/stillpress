import { beforeAll, expect, test } from 'vitest';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Profile } = await import('./Profile.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/profile']}><Profile /></t.router.MemoryRouter>);
});

const ME = { id: 4, login: 'carol', displayName: 'Carol', email: 'carol@example.com', role: 'author' };
const routes = (put: { status?: number; body?: unknown }) => ({
  'GET /session': { body: ME }, 'GET /site': { body: { importAllowed: false } }, 'PUT /users/4': put,
});

async function saveWith(view: ReturnType<typeof page>, name: string, email: string) {
  const nameField = await view.findByLabelText('Display name');
  await t.user.clear(nameField);
  await t.user.type(nameField, name);
  const emailField = view.getByLabelText('Email');
  await t.user.clear(emailField);
  await t.user.type(emailField, email);
  await t.user.click(view.getByRole('button', { name: 'Save' }));
}

test('shows the signed-in user', async () => {
  fakeApi(routes({ status: 204 }));
  const view = page();
  expect(((await view.findByLabelText('Display name')) as HTMLInputElement).value).toBe('Carol');
  expect((view.getByLabelText('Email') as HTMLInputElement).value).toBe('carol@example.com');
  expect(view.getByText('carol')).toBeTruthy();
});

test('saving sends the name and email', async () => {
  const calls = fakeApi(routes({ status: 204 }));
  const view = page();
  await saveWith(view, 'Caroline', 'caroline@example.com');
  expect((await view.findByRole('status')).textContent).toBe('Profile saved.');
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ displayName: 'Caroline', email: 'caroline@example.com' });
});

for (const [name, reply, field, text] of [
  ['a taken email', { status: 409, body: { error: 'email_taken' } }, 'Email', 'Another user has this email.'],
  ['a too long email', { status: 400, body: { field: 'email' } }, 'Email', 'Use an email address of at most 100 characters.'],
  ['a too long name', { status: 400, body: { field: 'displayName' } }, 'Display name', 'Enter a name of at most 250 characters.'],
] as const) {
  test(`${name} marks its field`, async () => {
    fakeApi(routes(reply));
    const view = page();
    await saveWith(view, 'Caroline', 'caroline@example.com');
    expect((await view.findByRole('alert')).textContent).toBe(text);
    expect(view.getByLabelText(field).getAttribute('aria-invalid')).toBe('true');
    expect(view.queryByRole('status')).toBeNull();
  });
}

test('any other refusal says the profile was not saved', async () => {
  fakeApi(routes({ status: 500 }));
  const view = page();
  await saveWith(view, 'Caroline', 'caroline@example.com');
  expect((await view.findByRole('alert')).textContent).toBe('Could not save the profile.');
});

test('a profile that cannot be loaded says so', async () => {
  fakeApi({ 'GET /session': { status: 500 }, 'GET /site': { body: { importAllowed: false } } });
  const view = page();
  expect((await view.findByRole('alert')).textContent).toBe('Could not load the profile.');
  expect(view.queryByRole('button', { name: 'Save' })).toBeNull();
});
