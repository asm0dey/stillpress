import 'altcha';
import { type HTMLAttributes, type Ref, useEffect, useRef, useState } from 'react';
import { __ } from '@wordpress/i18n';
import { adminFetch, api } from './api.ts';

declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      'altcha-widget': HTMLAttributes<HTMLElement> & { ref?: Ref<HTMLElement>; challenge: string; name: string };
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

/** The login options, null until they arrive; a failed fetch counts as mail on and no CAPTCHA, the server still checks. */
export function useLoginOptions(): LoginOptions | null {
  const [options, setOptions] = useState<LoginOptions | null>(null);
  useEffect(() => {
    let cancelled = false;
    api<LoginOptions>('/login-options').then((o) => !cancelled && setOptions(o),
      () => !cancelled && setOptions({ mail: true, captcha: 'none' }));
    return () => { cancelled = true; };
  }, []);
  return options;
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
const challengeFetch = (input: RequestInfo | URL) =>
  adminFetch(new URL(String(input), location.href).pathname.replace(/^\/admin\/api/, ''));

/** The provider's widget; onToken gets the token once solved, and undefined again when it expires. */
export function Captcha({ options, onToken }: { options: LoginOptions; onToken: (t?: string) => void }) {
  const ref = useRef<HTMLElement>(null);
  const callback = useRef(onToken);
  callback.current = onToken;

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
    }, () => {});
    return () => {
      gone = true;
      if (id) window.turnstile?.remove(id);
    };
  }, [options.captcha, options.turnstileSiteKey]);

  if (options.captcha === 'none') return null;
  if (options.captcha === 'altcha') {
    return <div className="field"><altcha-widget ref={ref} challenge="/admin/api/altcha/challenge" name="captcha" /></div>;
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

/**
 * What the four public forms share: the options, the token and honeypot to send, whether submit may go ahead, and
 * reset, which remounts the widget for a fresh token (Turnstile tokens are single use).
 */
export function useCaptcha() {
  const options = useLoginOptions();
  const [token, setToken] = useState<string>();
  const [website, setWebsite] = useState('');
  const [round, setRound] = useState(0);
  return {
    options,
    ready: options !== null && (options.captcha === 'none' || token !== undefined),
    fields: { captcha: token, website },
    reset: () => {
      setToken(undefined);
      setRound((r) => r + 1);
    },
    widgets: (
      <>
        {options && <Captcha key={round} options={options} onToken={setToken} />}
        <Honeypot value={website} onChange={setWebsite} />
      </>
    ),
  };
}

/** Shown when the server refuses the CAPTCHA token. */
export const CAPTCHA_FAILED = () => __("The check didn't pass. Try again.", 'stillpress');
