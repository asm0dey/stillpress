import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api } from './api.js';

/** The admin header; the Users link shows for administrators only. */
export default function Shell({ children }) {
  const navigate = useNavigate();
  const [role, setRole] = useState(null);
  useEffect(() => {
    api('/session').then((s) => setRole(s.role), () => {});
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
        </nav>
        <button type="button" onClick={logout}>Log out</button>
      </header>
      <main className="page">{children}</main>
    </>
  );
}
