import 'altcha';
import 'altcha/i18n/ru';
import { type HTMLAttributes, type Ref, useEffect, useRef, useState } from 'react';
import { __ } from '@wordpress/i18n';
import { type ApiError, adminFetch, api } from './api.ts';
import { locale } from './i18n/index.ts';

declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      'altcha-widget': HTMLAttributes<HTMLElement> & { ref?: Ref<HTMLElement>; challenge: string; name: string; language: string };
    }
  }
}

interface Turnstile {
  render(el: HTMLElement, options: { sitekey: string; callback: (token: string) => void; 'expired-callback': () => void }): string;
  remove(id: string): void;
}
declare global {
  interface Window { turnstile?: Turnstile }
}

/** GET /login-options: whether mail is set up and which CAPTCHA the public forms show. */
export type LoginOptions = { mail: boolean; captcha: 'none' | 'turnstile' | 'altcha'; turnstileSiteKey?: string };

/**
 * The login options, null until they arrive. When the fetch fails, `failed` is set and the options fall back to mail on
 * and no CAPTCHA, which the other forms can live with (the server still checks); ForgotPassword can't.
 */
export function useLoginOptions(): { options: LoginOptions | null; failed: boolean } {
  const [state, setState] = useState<{ options: LoginOptions | null; failed: boolean }>({ options: null, failed: false });
  useEffect(() => {
    let cancelled = false;
    api<LoginOptions>('/login-options').then((o) => !cancelled && setState({ options: o, failed: false }),
      () => !cancelled && setState({ options: { mail: true, captcha: 'none' }, failed: true }));
    return () => { cancelled = true; };
  }, []);
  return state;
}

const TURNSTILE = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
let turnstileScript: Promise<void> | undefined;
function loadTurnstile(): Promise<void> {
  return turnstileScript ??= new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = TURNSTILE;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = () => {
      turnstileScript = undefined;
      reject(new Error('turnstile'));
    };
    document.head.append(s);
  });
}

/**
 * The widget's own fetch would answer with a new CSRF token it doesn't keep, stranding the one the form then sends;
 * through adminFetch the new token is kept.
 */
const challengeFetch = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = new URL(String(input), location.href);
  return adminFetch(url.pathname.replace(/^\/admin\/api/, '') + url.search, init as Parameters<typeof adminFetch>[1]);
};

/** The provider's widget; onToken gets the token once solved, and undefined again when it expires. */
export function Captcha({ options, onToken, onError }: { options: LoginOptions; onToken: (t?: string) => void; onError?: () => void }) {
  const ref = useRef<HTMLElement>(null);
  const callback = useRef(onToken);
  callback.current = onToken;
  const failure = useRef(onError);
  failure.current = onError;

  useEffect(() => {
    const el = ref.current;
    if (!el || options.captcha === 'none') return;
    if (options.captcha === 'altcha') {
      const changed = (e: Event) => {
        const { state, payload } = (e as CustomEvent<{ state: string; payload?: string }>).detail;
        callback.current(state === 'verified' ? payload : undefined);
      };
      const widget = el as HTMLElementTagNameMap['altcha-widget'];
      // its methods exist only once it has loaded, which may be before or after this effect
      const configure = () => void widget.configure({ fetch: challengeFetch });
      if (typeof widget.configure === 'function') configure();
      el.addEventListener('load', configure);
      el.addEventListener('statechange', changed);
      return () => {
        el.removeEventListener('load', configure);
        el.removeEventListener('statechange', changed);
      };
    }
    let id: string | undefined;
    let gone = false;
    loadTurnstile().then(() => {
      if (gone || !window.turnstile) return;
      id = window.turnstile.render(el, {
        sitekey: options.turnstileSiteKey ?? '',
        callback: (t) => callback.current(t),
        'expired-callback': () => callback.current(undefined),
      });
    }, () => failure.current?.());
    return () => {
      gone = true;
      if (id) window.turnstile?.remove(id);
    };
  }, [options.captcha, options.turnstileSiteKey]);

  if (options.captcha === 'none') return null;
  if (options.captcha === 'altcha') {
    return <div className="field"><altcha-widget ref={ref} challenge="/admin/api/altcha/challenge" name="captcha" language={locale()} /></div>;
  }
  return <div className="field" ref={ref as Ref<HTMLDivElement>} />;
}

/** A field people never see or fill; bots that fill every input give themselves away. */
export function Honeypot({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="hp" aria-hidden="true">
      <label>{__('Website', 'stillpress')}
        <input name="website" tabIndex={-1} autoComplete="off" value={value} onChange={(e) => onChange(e.target.value)} />
      </label>
    </div>
  );
}

/** Whether the server may have used up the token: a network failure, 429 and CSRF refusals all come before the check. */
export const tokenMaybeSpent = (err: ApiError) => err.status !== 0 && err.status !== 429 && err.error !== 'csrf';

/**
 * What the four public forms share: the options, the token and honeypot to send, whether submit may go ahead, and
 * failed, which a form calls with the server's refusal. It remounts the widget for a fresh token only when the server
 * may have spent this one (Turnstile tokens are single use); a network failure, 429 and CSRF refusals come before the
 * check, so the solved token stays.
 */
export function useCaptcha() {
  const { options, failed: optionsFailed } = useLoginOptions();
  const [token, setToken] = useState<string>();
  const [website, setWebsite] = useState('');
  const [round, setRound] = useState(0);
  const [error, setError] = useState<string>();
  return {
    options,
    optionsFailed,
    error,
    ready: options !== null && (options.captcha === 'none' || token !== undefined),
    fields: { captcha: token, website },
    failed: (err: ApiError) => {
      if (!tokenMaybeSpent(err)) return;
      setError(undefined);
      setToken(undefined);
      setRound((r) => r + 1);
    },
    widgets: (
      <>
        {options && <Captcha key={round} options={options} onToken={setToken} onError={() => setError(CAPTCHA_LOAD_FAILED())} />}
        {error && <p role="alert" className="error">{error}</p>}
        <Honeypot value={website} onChange={setWebsite} />
      </>
    ),
  };
}

const CAPTCHA_LOAD_FAILED = () => __('Could not load the CAPTCHA. Check your connection and reload the page.', 'stillpress');

/** Shown when the server refuses the CAPTCHA token. */
export const CAPTCHA_FAILED = () => __("The check didn't pass. Try again.", 'stillpress');
