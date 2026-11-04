import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { api } from './api.js';
import './theme.css';
import Setup from './pages/Setup.jsx';
import Login from './pages/Login.jsx';
import SetPassword from './pages/SetPassword.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Users from './pages/Users.jsx';
import Import from './pages/Import.jsx';

const PUBLIC = ['/setup', '/login', '/set-password'];

/** Sends a fresh install to the wizard and an anonymous visitor to the login page. */
function Gate({ children }) {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);
  useEffect(() => {
    (async () => {
      let target = null;
      try {
        const { required } = await api('/setup');
        if (required) {
          if (pathname !== '/setup') target = '/setup';
        } else if (pathname === '/setup') {
          target = '/';
        } else if (!PUBLIC.includes(pathname)) {
          await api('/session');
        }
      } catch (e) {
        if (e.status === 401) target = '/login?next=' + encodeURIComponent(pathname + search);
      }
      // render nothing until the redirect lands, so a protected page never mounts for an anonymous visitor
      if (target) navigate(target, { replace: true });
      else setReady(true);
    })();
  }, [pathname]);
  return ready ? children : null;
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter basename="/admin">
      <Gate>
        <Routes>
          <Route path="/setup" element={<Setup />} />
          <Route path="/login" element={<Login />} />
          <Route path="/set-password" element={<SetPassword />} />
          <Route path="/" element={<Dashboard />} />
          <Route path="/users" element={<Users />} />
          <Route path="/import" element={<Import />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Gate>
    </BrowserRouter>
  </StrictMode>,
);
