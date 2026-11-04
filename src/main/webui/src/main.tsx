import { type ReactNode, StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { type ApiError, api } from './api.ts';
import './theme.css';
import Setup from './pages/Setup.tsx';
import Login from './pages/Login.tsx';
import SetPassword from './pages/SetPassword.tsx';
import Dashboard from './pages/Dashboard.tsx';
import Users from './pages/Users.tsx';
import Import from './pages/Import.tsx';
import Settings from './pages/Settings.tsx';

const PUBLIC = ['/setup', '/login', '/set-password'];

/** Sends a fresh install to the wizard and an anonymous visitor to the login page. */
function Gate({ children }: { children: ReactNode }) {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);
  useEffect(() => {
    (async () => {
      let target: string | null = null;
      try {
        const { required } = await api<{ required: boolean }>('/setup');
        if (required) {
          if (pathname !== '/setup') target = '/setup';
        } else if (pathname === '/setup') {
          target = '/';
        } else if (!PUBLIC.includes(pathname)) {
          await api('/session');
        }
      } catch (e) {
        if ((e as ApiError).status === 401) target = '/login?next=' + encodeURIComponent(pathname + search);
      }
      // render nothing until the redirect lands, so a protected page never mounts for an anonymous visitor
      if (target) navigate(target, { replace: true });
      else setReady(true);
    })();
  }, [pathname]);
  return ready ? children : null;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter basename="/admin">
      <Gate>
        <Routes>
          <Route path="/setup" element={<Setup />} />
          <Route path="/login" element={<Login />} />
          <Route path="/set-password" element={<SetPassword />} />
          <Route path="/" element={<Dashboard />} />
          <Route path="/users" element={<Users />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/import" element={<Import />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Gate>
    </BrowserRouter>
  </StrictMode>,
);
