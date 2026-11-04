import { Link, useNavigate } from 'react-router';
import { type ReactNode, useEffect, useState } from 'react';
import { __ } from '@wordpress/i18n';
import { type Role, type SessionUser, type Site, api } from './api.ts';
import { type Locale, locale } from './i18n/index.ts';

/** Each admin language in its own name. */
const LANGUAGES: [Locale, string][] = [['en', 'English'], ['ru', 'Русский']];

/**
 * The admin header: Posts and Reusable blocks for everyone; Pages, Categories and Tags for editors and administrators; the rest for
 * administrators only. The language switch stores the user's choice and reloads: Gutenberg translates its blocks once,
 * when they register.
 */
export default function Shell({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const [role, setRole] = useState<Role | null>(null);
  const [importAllowed, setImportAllowed] = useState(false);
  useEffect(() => {
    api<SessionUser>('/session').then((s) => setRole(s.role), () => {});
    api<Site>('/site').then((s) => setImportAllowed(s.importAllowed), () => {});
  }, []);

  async function switchLanguage(l: Locale) {
    try {
      await api('/session/locale', { method: 'PUT', body: { locale: l } });
      location.reload();
    } catch { /* the select stays on the language still shown */ }
  }

  async function logout() {
    await api('/logout', { method: 'POST' }).catch(() => {});
    navigate('/login');
  }

  return (
    <>
      <header className="bar">
        <nav>
          <Link to="/" className="brand">Stillpress</Link>
          {role && <Link to="/posts">{__('Posts', 'stillpress')}</Link>}
          {(role === 'administrator' || role === 'editor') && <Link to="/pages">{__('Pages', 'stillpress')}</Link>}
          {role && <Link to="/blocks">{__('Reusable blocks', 'stillpress')}</Link>}
          {(role === 'administrator' || role === 'editor') && <Link to="/categories">{__('Categories', 'stillpress')}</Link>}
          {(role === 'administrator' || role === 'editor') && <Link to="/tags">{__('Tags', 'stillpress')}</Link>}
          {role === 'administrator' && <Link to="/redirects">{__('Redirects', 'stillpress')}</Link>}
          {role === 'administrator' && <Link to="/users">{__('Users', 'stillpress')}</Link>}
          {role === 'administrator' && <Link to="/settings">{__('Settings', 'stillpress')}</Link>}
          {role === 'administrator' && importAllowed && <Link to="/import">{__('Import', 'stillpress')}</Link>}
        </nav>
        <select aria-label={__('Language', 'stillpress')} value={locale()} onChange={(e) => switchLanguage(e.target.value as Locale)}>
          {LANGUAGES.map(([l, name]) => <option key={l} value={l} lang={l}>{name}</option>)}
        </select>
        <button type="button" onClick={logout}>{__('Log out', 'stillpress')}</button>
      </header>
      <main className="page">{children}</main>
    </>
  );
}
