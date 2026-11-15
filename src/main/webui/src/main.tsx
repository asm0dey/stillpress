import { StrictMode, Suspense, lazy } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router';
import { __ } from '@wordpress/i18n';
import { type SessionUser, api } from './api.ts';
import Gate from './Gate.tsx';
import { browserLocale, loadLocale, loadWordPressLocale } from './i18n/index.ts';
import './theme.css';
import Setup from './pages/Setup.tsx';
import Login from './pages/Login.tsx';
import SetPassword from './pages/SetPassword.tsx';
import ForgotPassword from './pages/ForgotPassword.tsx';
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
const Security = lazy(() => admin().then((m) => ({ default: m.Security })));
const TwoFactor = lazy(() => admin().then((m) => ({ default: m.TwoFactor })));

// Gutenberg is large: only the editor route downloads it, with WordPress's translation of its strings
const Editor = lazy(() => loadWordPressLocale().then(() => import('./editor/Editor.tsx')));
const BlockEditor = lazy(() => loadWordPressLocale().then(() => import('./editor/BlockEditor.tsx')));
// the diff library comes with the revisions screens only
const Revisions = lazy(() => import('./pages/Revisions.tsx'));

// the signed-in user's language, or the browser's before anyone has signed in; loaded before anything renders
void api<SessionUser>('/session').then((s) => s.locale, () => browserLocale()).then(loadLocale)
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
          <Route path="/forgot-password" element={<ForgotPassword />} />
          <Route path="/" element={<Dashboard />} />
          <Route path="/users" element={<Users />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/security" element={<Security />} />
          <Route path="/two-factor" element={<TwoFactor />} />
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
