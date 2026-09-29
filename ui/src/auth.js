// Sign-in with the identity service: OAuth 2.0 authorization code + PKCE, as a public client (no secret in the page).
//
// Tokens are kept in memory only. A page reload loses them; the page then simply runs the authorize redirect again,
// and the identity service's own session cookie makes that instant, with no password prompt. That keeps tokens out
// of localStorage/sessionStorage, where any XSS could read them. Only the short-lived PKCE verifier and state are
// stored (sessionStorage), because they must survive the redirect.

const PENDING = 'ledger.oauth.pending';
const SKEW_MS = 30_000; // refresh a little before the access token expires

export function randomString(bytes = 32, cryptoImpl = globalThis.crypto) {
  const buf = cryptoImpl.getRandomValues(new Uint8Array(bytes));
  return base64Url(buf);
}

/** PKCE S256: BASE64URL(SHA-256(verifier)). */
export async function challengeFor(verifier, cryptoImpl = globalThis.crypto) {
  const digest = await cryptoImpl.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

function base64Url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** The redirect URI is the page's own root: the ledger serves the app only at "/", so the code comes back there. */
export function redirectUriFor(location) {
  return `${location.origin}/`;
}

export function buildAuthorizeUrl(config, { redirectUri, state, challenge }) {
  const params = new URLSearchParams({
    client_id: config.clientId,
    response_type: 'code',
    redirect_uri: redirectUri,
    scope: config.scope,
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  return `${config.identityUrl}/connect/authorize?${params}`;
}

/** True when the current URL is the identity service sending the user back (with a code or an error). */
export function isCallback(search) {
  const q = new URLSearchParams(search);
  return q.has('state') && (q.has('code') || q.has('error'));
}

/** Validates a callback against what was stored before the redirect and returns the authorization code. */
export function codeFromCallback(search, pending) {
  const q = new URLSearchParams(search);
  if (!pending) throw new Error('No sign-in was in progress. Please sign in again.');
  if (q.get('error')) throw new Error(q.get('error_description') || q.get('error'));
  // CSRF protection: the state must be the one this browser generated.
  if (q.get('state') !== pending.state) {
    throw new Error('The sign-in response does not match this browser (state mismatch).');
  }
  const code = q.get('code');
  if (!code) throw new Error('The identity service returned no authorization code.');
  return code;
}

/** Reads display claims from a token that came straight from our token endpoint (signature is the ledger's job). */
export function readClaims(jwt) {
  const part = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  const json = decodeURIComponent(
    [...atob(part)].map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
  );
  return JSON.parse(json);
}

export class OAuthSession {
  constructor(
    config,
    { storage = sessionStorage, location = window.location, fetchImpl = (...args) => fetch(...args) } = {},
  ) {
    this.config = config;
    this.storage = storage;
    this.location = location;
    this.fetch = fetchImpl;
    this.accessToken = null;
    this.refreshToken = null;
    this.expiresAt = 0;
    this.refreshing = null;
    this.user = null;
  }

  /** Sends the browser to the identity service's sign-in page. */
  async login() {
    const verifier = randomString(48);
    const state = randomString(16);
    this.storage.setItem(PENDING, JSON.stringify({ verifier, state }));
    const url = buildAuthorizeUrl(this.config, {
      redirectUri: redirectUriFor(this.location),
      state,
      challenge: await challengeFor(verifier),
    });
    this.location.assign(url);
  }

  /** Finishes the redirect: checks state, exchanges the code for tokens. */
  async handleCallback(search) {
    const raw = this.storage.getItem(PENDING);
    this.storage.removeItem(PENDING); // single use, whatever happens next
    const pending = raw ? JSON.parse(raw) : null;
    const code = codeFromCallback(search, pending);
    await this.tokenRequest({
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUriFor(this.location),
      code_verifier: pending.verifier,
    });
  }

  /** A valid access token, refreshed first if it is about to expire. Null when the user must sign in again. */
  async getAccessToken() {
    if (this.accessToken && Date.now() < this.expiresAt - SKEW_MS) return this.accessToken;
    if (!this.refreshToken) return null;
    // One refresh at a time: two parallel requests presenting the same refresh token look exactly like token theft to
    // the identity service's reuse detection, which would end the whole session.
    this.refreshing ??= this.tokenRequest({ grant_type: 'refresh_token', refresh_token: this.refreshToken })
      .catch(() => this.clear())
      .finally(() => {
        this.refreshing = null;
      });
    await this.refreshing;
    return this.accessToken;
  }

  /** Revokes the refresh token, then ends the identity service's session so the next sign-in asks for a password. */
  async logout() {
    const token = this.refreshToken;
    this.clear();
    if (token) {
      await this.fetch(`${this.config.identityUrl}/connect/revoke`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ client_id: this.config.clientId, token, token_type_hint: 'refresh_token' }),
      }).catch(() => undefined);
    }
    const back = encodeURIComponent(redirectUriFor(this.location));
    this.location.assign(`${this.config.identityUrl}/account/logout?post_logout_redirect_uri=${back}`);
  }

  clear() {
    this.accessToken = null;
    this.refreshToken = null;
    this.expiresAt = 0;
    this.user = null;
  }

  async tokenRequest(body) {
    const res = await this.fetch(`${this.config.identityUrl}/connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: this.config.clientId, ...body }),
    });
    if (!res.ok) throw new Error(`Sign-in failed: the identity service answered ${res.status}.`);
    const t = await res.json();
    this.accessToken = t.access_token;
    this.refreshToken = t.refresh_token ?? this.refreshToken;
    this.expiresAt = Date.now() + t.expires_in * 1000;
    const claims = readClaims(t.id_token ?? t.access_token);
    this.user = {
      sub: claims.sub,
      name: claims.name ?? claims.email ?? claims.sub,
      scopes: String(readClaims(t.access_token).scope ?? '').split(' ').filter(Boolean),
    };
  }
}
