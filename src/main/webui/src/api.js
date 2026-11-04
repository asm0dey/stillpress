/**
 * Calls the admin JSON API. Every request carries the CSRF header; a FormData body goes as multipart (the browser
 * sets its Content-Type), anything else as JSON. Non-2xx throws {status, error, field, message}.
 */
export async function api(path, { method = 'GET', body } = {}) {
  const headers = { 'X-Requested-With': 'stillpress' };
  const form = body instanceof FormData;
  if (body !== undefined && !form) headers['Content-Type'] = 'application/json';
  const res = await fetch('/admin/api' + path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined || form ? body : JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) throw { status: res.status, error: data?.error, field: data?.field, message: data?.message };
  return data;
}
