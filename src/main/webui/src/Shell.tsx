import { Link, useNavigate } from 'react-router';
import { type ReactNode, useEffect, useState } from 'react';
import { __ } from '@wordpress/i18n';
import { type Role, type SessionUser, type Site, api, asApiError, mayOpen } from './api.ts';
import { type Locale, locale } from './i18n/index.ts';

/** Each admin language in its own name. */
const LANGUAGES: [Locale, string][] = [['en', 'English'], ['ru', 'Русский']];

/**
 * The admin header: Posts, Reusable blocks and Profile for everyone, the other screens for the roles that may open them
 * ({@link mayOpen}). The language switch stores the user's choice and reloads: Gutenberg translates its blocks once,
 * when they register.
 */
export default function Shell({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const [role, setRole] = useState<Role | null>(null);
  const [importAllowed, setImportAllowed] = useState(false);
  const [languageError, setLanguageError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    api<SessionUser>('/session').then((s) => !cancelled && setRole(s.role), () => {});
    api<Site>('/site').then((s) => !cancelled && setImportAllowed(s.importAllowed), () => {});
    return () => { cancelled = true; };
  }, []);
  // links to the screens the gate lets this role open
  const may = (path: string) => role !== null && mayOpen(role, path);

  async function switchLanguage(l: Locale) {
    setLanguageError(null);
    try {
      await api('/session/locale', { method: 'PUT', body: { locale: l } });
      location.reload();
    } catch (e) {
      const err = asApiError(e);
      setLanguageError(err.error === 'csrf' ? err.message! : __('Could not change the language.', 'stillpress'));
    }
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
          {may('/pages') && <Link to="/pages">{__('Pages', 'stillpress')}</Link>}
          {role && <Link to="/blocks">{__('Reusable blocks', 'stillpress')}</Link>}
          {may('/categories') && <Link to="/categories">{__('Categories', 'stillpress')}</Link>}
          {may('/tags') && <Link to="/tags">{__('Tags', 'stillpress')}</Link>}
          {may('/redirects') && <Link to="/redirects">{__('Redirects', 'stillpress')}</Link>}
          {may('/users') && <Link to="/users">{__('Users', 'stillpress')}</Link>}
          {may('/settings') && <Link to="/settings">{__('Settings', 'stillpress')}</Link>}
          {may('/import') && importAllowed && <Link to="/import">{__('Import', 'stillpress')}</Link>}
          {role && <Link to="/profile">{__('Profile', 'stillpress')}</Link>}
        </nav>
        <select aria-label={__('Language', 'stillpress')} value={locale()} onChange={(e) => switchLanguage(e.target.value as Locale)}>
          {LANGUAGES.map(([l, name]) => <option key={l} value={l} lang={l}>{name}</option>)}
        </select>
        <button type="button" onClick={logout}>{__('Log out', 'stillpress')}</button>
      </header>
      <main className="page">
        {languageError && <p role="alert" className="error">{languageError}</p>}
        {children}
      </main>
    </>
  );
}
