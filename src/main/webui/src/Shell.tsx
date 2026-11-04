import { Link, useNavigate } from 'react-router';
import { type ReactNode, useEffect, useState } from 'react';
import { type Role, type SessionUser, api } from './api.ts';

/** The admin header; the Users and Import links show for administrators only. */
export default function Shell({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const [role, setRole] = useState<Role | null>(null);
  useEffect(() => {
    api<SessionUser>('/session').then((s) => setRole(s.role), () => {});
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
          {role === 'administrator' && <Link to="/import">Import</Link>}
        </nav>
        <button type="button" onClick={logout}>Log out</button>
      </header>
      <main className="page">{children}</main>
    </>
  );
}
