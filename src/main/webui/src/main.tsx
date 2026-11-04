import { type ReactNode, StrictMode, Suspense, lazy, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { __ } from '@wordpress/i18n';
import { type SessionUser, api, asApiError, mayOpen } from './api.ts';
import { browserLocale, loadLocale, loadWordPressLocale } from './i18n/index.ts';
import './theme.css';
import Setup from './pages/Setup.tsx';
import Login from './pages/Login.tsx';
import SetPassword from './pages/SetPassword.tsx';
import Dashboard from './pages/Dashboard.tsx';

// the larger admin screens come as one chunk; login, setup and the dashboard stay in the entry
const admin = () => import('./pages/admin.ts');
const Users = lazy(() => admin().then((m) => ({ default: m.Users })));
const Import = lazy(() => admin().then((m) => ({ default: m.Import })));
const Settings = lazy(() => admin().then((m) => ({ default: m.Settings })));
const Terms = lazy(() => admin().then((m) => ({ default: m.Terms })));
const Redirects = lazy(() => admin().then((m) => ({ default: m.Redirects })));
const Items = lazy(() => admin().then((m) => ({ default: m.Items })));
const Blocks = lazy(() => admin().then((m) => ({ default: m.Blocks })));
const Profile = lazy(() => admin().then((m) => ({ default: m.Profile })));

// Gutenberg is large: only the editor route downloads it, with WordPress's translation of its strings
const Editor = lazy(() => loadWordPressLocale().then(() => import('./editor/Editor.tsx')));
const BlockEditor = lazy(() => loadWordPressLocale().then(() => import('./editor/BlockEditor.tsx')));
// the diff library comes with the revisions screens only
const Revisions = lazy(() => import('./pages/Revisions.tsx'));

const PUBLIC = ['/setup', '/login', '/set-password'];
/**
 * Sends a fresh install to the wizard, an anonymous visitor to the login page and a role to the dashboard from a
 * screen it may not use. Checks again on every navigation, rendering nothing until the check lands.
 */
function Gate({ children }: { children: ReactNode }) {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  // the path the last check let through; a new path renders nothing until its own check lands
  const [passed, setPassed] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let target: string | null = null;
      try {
        const { required } = await api<{ required: boolean }>('/setup');
        if (required) {
          if (pathname !== '/setup') target = '/setup';
        } else if (pathname === '/setup') {
          target = '/';
        } else if (!PUBLIC.includes(pathname)) {
          const { role } = await api<SessionUser>('/session');
          if (!mayOpen(role, pathname)) target = '/';
        }
      } catch (e) {
        if (asApiError(e).status === 401) target = '/login?next=' + encodeURIComponent(pathname + search);
      }
      if (cancelled) return;
      // render nothing until the redirect lands, so a protected page never mounts for an anonymous visitor
      if (target) navigate(target, { replace: true });
      else setPassed(pathname);
    })();
    return () => { cancelled = true; };
  }, [pathname]);
  return passed === pathname ? children : null;
}

// the signed-in user's language, or the browser's before anyone has signed in; loaded before anything renders
api<SessionUser>('/session').then((s) => s.locale, () => browserLocale()).then(loadLocale)
  // a translation chunk that fails to load leaves English, not a blank admin
  .catch(() => {}).then(() => createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter basename="/admin">
      <Gate>
        <Suspense fallback={<p role="status">{__('Loading…', 'stillpress')}</p>}>
        <Routes>
          <Route path="/setup" element={<Setup />} />
          <Route path="/login" element={<Login />} />
          <Route path="/set-password" element={<SetPassword />} />
          <Route path="/" element={<Dashboard />} />
          <Route path="/users" element={<Users />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/import" element={<Import />} />
          <Route path="/categories" element={<Terms taxonomy="category" />} />
          <Route path="/tags" element={<Terms taxonomy="post_tag" />} />
          <Route path="/redirects" element={<Redirects />} />
          <Route path="/posts" element={<Items type="post" />} />
          <Route path="/pages" element={<Items type="page" />} />
          <Route path="/edit/:id" element={<Suspense fallback={<p role="status">{__('Loading the editor…', 'stillpress')}</p>}><Editor /></Suspense>} />
          <Route path="/edit/:id/revisions" element={<Revisions kind="items" />} />
          <Route path="/blocks/:id/revisions" element={<Revisions kind="blocks" />} />
          <Route path="/blocks" element={<Blocks />} />
          <Route path="/blocks/:id" element={<Suspense fallback={<p role="status">{__('Loading the editor…', 'stillpress')}</p>}><BlockEditor /></Suspense>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        </Suspense>
      </Gate>
    </BrowserRouter>
  </StrictMode>,
));
