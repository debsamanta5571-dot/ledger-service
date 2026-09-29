import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  buildAuthorizeUrl,
  challengeFor,
  codeFromCallback,
  isCallback,
  OAuthSession,
  randomString,
  readClaims,
  redirectUriFor,
} from './auth.js';

const CONFIG = { identityUrl: 'http://localhost:5001', clientId: 'ledger-ui', scope: 'openid accounts:read' };

const b64url = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');
const jwt = (claims) => `${b64url({ alg: 'RS256' })}.${b64url(claims)}.sig`;

test('PKCE challenge is BASE64URL(SHA-256(verifier)) with no padding (RFC 7636 appendix B vector)', async () => {
  const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
  assert.equal(await challengeFor(verifier), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  const other = randomString(48);
  assert.equal(await challengeFor(other), createHash('sha256').update(other).digest('base64url'));
});

test('randomString is URL-safe and not repeated', () => {
  const a = randomString(48);
  assert.match(a, /^[A-Za-z0-9_-]+$/);
  assert.notEqual(a, randomString(48));
});

test('authorize URL carries the PKCE and client parameters', () => {
  const url = new URL(buildAuthorizeUrl(CONFIG, { redirectUri: 'http://127.0.0.1:8080/', state: 's1', challenge: 'c1' }));
  assert.equal(url.origin + url.pathname, 'http://localhost:5001/connect/authorize');
  const p = url.searchParams;
  assert.equal(p.get('client_id'), 'ledger-ui');
  assert.equal(p.get('response_type'), 'code');
  assert.equal(p.get('redirect_uri'), 'http://127.0.0.1:8080/');
  assert.equal(p.get('code_challenge_method'), 'S256');
  assert.equal(p.get('code_challenge'), 'c1');
  assert.equal(p.get('state'), 's1');
  assert.equal(p.get('scope'), 'openid accounts:read');
});

test('redirect URI is the page root', () => {
  assert.equal(redirectUriFor({ origin: 'http://127.0.0.1:8080' }), 'http://127.0.0.1:8080/');
});

test('callback detection', () => {
  assert.equal(isCallback('?code=a&state=b'), true);
  assert.equal(isCallback('?error=access_denied&state=b'), true);
  assert.equal(isCallback('?code=a'), false);
  assert.equal(isCallback(''), false);
});

test('callback validation rejects a wrong state, an error, a missing code, and no sign-in in progress', () => {
  const pending = { state: 'good', verifier: 'v' };
  assert.equal(codeFromCallback('?code=abc&state=good', pending), 'abc');
  assert.throws(() => codeFromCallback('?code=abc&state=evil', pending), /state mismatch/);
  assert.throws(() => codeFromCallback('?error=access_denied&error_description=nope&state=good', pending), /nope/);
  assert.throws(() => codeFromCallback('?state=good', pending), /no authorization code/);
  assert.throws(() => codeFromCallback('?code=abc&state=good', null), /No sign-in/);
});

test('readClaims decodes base64url JSON including non-ASCII', () => {
  assert.deepEqual(readClaims(jwt({ sub: 'u1', name: 'Zoë' })), { sub: 'u1', name: 'Zoë' });
});

function memoryStorage() {
  const m = new Map();
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => m.set(k, v), removeItem: (k) => m.delete(k) };
}

test('full flow: login stores PKCE state, callback exchanges the code with the matching verifier', async () => {
  const storage = memoryStorage();
  const location = { origin: 'http://127.0.0.1:8080', assign: (u) => (location.went = u) };
  const requests = [];
  const fetchImpl = async (url, init) => {
    requests.push({ url, body: Object.fromEntries(init.body) });
    return {
      ok: true,
      json: async () => ({
        access_token: jwt({ sub: 'u1', scope: 'accounts:read transfers:write' }),
        id_token: jwt({ sub: 'u1', name: 'Ada' }),
        refresh_token: 'r1',
        expires_in: 600,
      }),
    };
  };
  const session = new OAuthSession(CONFIG, { storage, location, fetchImpl });

  await session.login();
  const authorize = new URL(location.went);
  const state = authorize.searchParams.get('state');

  await session.handleCallback(`?code=the-code&state=${state}`);

  const tokenCall = requests[0];
  assert.equal(tokenCall.url, 'http://localhost:5001/connect/token');
  assert.equal(tokenCall.body.grant_type, 'authorization_code');
  assert.equal(tokenCall.body.code, 'the-code');
  assert.equal(tokenCall.body.client_id, 'ledger-ui');
  // The verifier sent now must hash to the challenge sent before the redirect.
  assert.equal(await challengeFor(tokenCall.body.code_verifier), authorize.searchParams.get('code_challenge'));
  assert.equal(session.user.name, 'Ada');
  assert.deepEqual(session.user.scopes, ['accounts:read', 'transfers:write']);
  assert.ok(await session.getAccessToken());

  // The PKCE state is single-use: replaying the same callback fails.
  await assert.rejects(session.handleCallback(`?code=the-code&state=${state}`), /No sign-in/);
});

test('an expired access token is refreshed exactly once even when many requests need it at the same time', async () => {
  let refreshes = 0;
  const fetchImpl = async (url, init) => {
    refreshes++;
    assert.equal(Object.fromEntries(init.body).grant_type, 'refresh_token');
    await new Promise((r) => setTimeout(r, 20));
    return { ok: true, json: async () => ({ access_token: jwt({ sub: 'u1' }), refresh_token: 'r2', expires_in: 600 }) };
  };
  const session = new OAuthSession(CONFIG, { storage: memoryStorage(), location: {}, fetchImpl });
  session.refreshToken = 'r1';
  session.expiresAt = 0; // expired

  const tokens = await Promise.all([1, 2, 3, 4, 5].map(() => session.getAccessToken()));

  assert.equal(refreshes, 1, 'parallel refreshes would trip the identity service reuse detection');
  assert.ok(tokens.every((t) => t && t === tokens[0]));
  assert.equal(session.refreshToken, 'r2');
});

test('a failed refresh signs the user out instead of retrying', async () => {
  const fetchImpl = async () => ({ ok: false, status: 400 });
  const session = new OAuthSession(CONFIG, { storage: memoryStorage(), location: {}, fetchImpl });
  session.refreshToken = 'revoked';
  assert.equal(await session.getAccessToken(), null);
  assert.equal(session.refreshToken, null);
});
