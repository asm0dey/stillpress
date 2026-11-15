interface WebAuthnClient {
  loginClientSteps(u: { username?: string }): Promise<unknown>;
  registerClientSteps(u: { username: string; displayName: string }): Promise<unknown>;
}
declare global {
  interface Window { WebAuthn?: new () => WebAuthnClient }
}

let script: Promise<WebAuthnClient> | undefined;
/** The extension's own client script, appended once. */
export function loadWebAuthn(): Promise<WebAuthnClient> {
  return script ??= new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = '/q/webauthn/webauthn.js';
    s.async = true;
    s.onload = () => (window.WebAuthn ? resolve(new window.WebAuthn()) : (script = undefined, reject(new Error('webauthn'))));
    s.onerror = () => {
      script = undefined;
      reject(new Error('webauthn'));
    };
    document.head.append(s);
  });
}

/** The browser prompt for a discoverable passkey; the JSON goes to /login/passkey or /login/passkey-step. */
export const passkeyAssertion = async () => (await loadWebAuthn()).loginClientSteps({});
/** The browser prompt to create a passkey; the JSON goes to the registration endpoint. */
export const passkeyAttestation = async (login: string, displayName: string) =>
  (await loadWebAuthn()).registerClientSteps({ username: login, displayName });
export const passkeysSupported = (): boolean => !!window.PublicKeyCredential;
