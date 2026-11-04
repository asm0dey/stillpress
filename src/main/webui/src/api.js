/**
 * Calls the admin JSON API. Every request carries the CSRF header; non-2xx throws {status, error, field}.
 */
export async function api(path, { method = 'GET', body } = {}) {
  const headers = { 'X-Requested-With': 'stillpress' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch('/admin/api' + path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) throw { status: res.status, error: data?.error, field: data?.field };
  return data;
}
