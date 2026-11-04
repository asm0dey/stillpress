import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { type ApiError, type Site, api } from '../api.ts';
import Shell from '../Shell.tsx';

export default function Dashboard() {
  const navigate = useNavigate();
  const [site, setSite] = useState<Site | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Site>('/site').then(setSite, (e: ApiError) => (e.status === 401 ? navigate('/login') : setError('Could not load the site.')));
  }, []);

  return (
    <Shell>
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
    </Shell>
  );
}
