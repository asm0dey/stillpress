import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api.js';

export default function Dashboard() {
  const navigate = useNavigate();
  const [site, setSite] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    api('/site').then(setSite, (e) => (e.status === 401 ? navigate('/login') : setError('Could not load the site.')));
  }, []);

  async function logout() {
    await api('/logout', { method: 'POST' }).catch(() => {});
    navigate('/login');
  }

  return (
    <>
      <header className="bar">
        <span className="brand">Stillpress</span>
        <button type="button" onClick={logout}>Log out</button>
      </header>
      <main className="page">
        {error && <p role="alert" className="error">{error}</p>}
        {site && (
          <>
            <h1>{site.title || 'Untitled site'}</h1>
            <p><a href={site.homeUrl || '/'}>View site</a></p>
            <dl className="stats">
              <div><dt>Posts and pages</dt><dd>{site.items}</dd></div>
              <div><dt>Comments</dt><dd>{site.comments}</dd></div>
            </dl>
          </>
        )}
      </main>
    </>
  );
}
