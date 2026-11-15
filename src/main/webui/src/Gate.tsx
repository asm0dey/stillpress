import { type ReactNode, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { type SessionUser, api, asApiError, mayOpen } from './api.ts';

const PUBLIC = ['/setup', '/login', '/set-password', '/forgot-password'];
/**
 * Sends a fresh install to the wizard, an anonymous visitor to the login page and a role to the dashboard from a
 * screen it may not use. Checks again on every navigation, rendering nothing until the check lands.
 */
export default function Gate({ children }: { children: ReactNode }) {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  // the path the last check let through; a new path renders nothing until its own check lands
  const [passed, setPassed] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      let target: string | null = null;
      try {
        const { required } = await api<{ required: boolean }>('/setup');
        if (required) {
          if (pathname !== '/setup') target = '/setup';
        } else if (pathname === '/setup') {
          target = '/';
        } else if (!PUBLIC.includes(pathname)) {
          const { role, enrollRequired } = await api<SessionUser>('/session');
          if (enrollRequired && pathname !== '/two-factor') target = '/two-factor';
          else if (!mayOpen(role, pathname)) target = '/';
        }
      } catch (e) {
        if (asApiError(e).status === 401) target = '/login?next=' + encodeURIComponent(pathname + search);
      }
      if (cancelled) return;
      // render nothing until the redirect lands, so a protected page never mounts for an anonymous visitor
      if (target) void navigate(target, { replace: true });
      else setPassed(pathname);
    })();
    return () => { cancelled = true; };
  }, [pathname]);
  return passed === pathname ? children : null;
}
