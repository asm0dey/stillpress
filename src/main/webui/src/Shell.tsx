import { Link, useNavigate } from 'react-router';
import { type ReactNode, useEffect, useState } from 'react';
import { type Role, type SessionUser, type Site, api } from './api.ts';

/** The admin header; the Users, Settings and Import links show for administrators only. */
export default function Shell({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const [role, setRole] = useState<Role | null>(null);
  const [importAllowed, setImportAllowed] = useState(false);
  useEffect(() => {
    api<SessionUser>('/session').then((s) => setRole(s.role), () => {});
    api<Site>('/site').then((s) => setImportAllowed(s.importAllowed), () => {});
  }, []);

  async function logout() {
    await api('/logout', { method: 'POST' }).catch(() => {});
    navigate('/login');
  }

  return (
    <>
      <header className="bar">
        <nav>
          <Link to="/" className="brand">Stillpress</Link>
          {role === 'administrator' && <Link to="/users">Users</Link>}
          {role === 'administrator' && <Link to="/settings">Settings</Link>}
          {role === 'administrator' && importAllowed && <Link to="/import">Import</Link>}
        </nav>
        <button type="button" onClick={logout}>Log out</button>
      </header>
      <main className="page">{children}</main>
    </>
  );
}
