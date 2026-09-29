// Thin fetch wrapper. Errors from the service are RFC 7807 problem+json; they are surfaced as ApiError.

const BASE = import.meta.env.VITE_API_BASE ?? '/api';

export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || problem?.title || `Request failed (${status})`);
    this.status = status;
    this.problem = problem;
  }
}

/**
 * How to authenticate. Either a signed-in user (Bearer access token from the identity service) or an API key.
 * `headers()` is async because a user's token may need refreshing first.
 */
export function apiKeyCredential(key) {
  return { kind: 'apiKey', headers: async () => ({ 'X-API-Key': key }) };
}

export function oauthCredential(session) {
  return {
    kind: 'oauth',
    headers: async () => {
      const token = await session.getAccessToken();
      if (!token) throw new ApiError(401, { title: 'Signed out', detail: 'Your sign-in has expired. Please sign in again.' });
      return { Authorization: `Bearer ${token}` };
    },
  };
}

/** Fetches public, unauthenticated endpoints (the sign-in configuration). */
export async function publicGet(path) {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new ApiError(res.status, null);
  return res.json();
}

export async function api(auth, path, { method = 'GET', body, headers = {} } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      ...(await auth.headers()),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) throw new ApiError(res.status, data);
  return { data, replayed: res.headers.get('Idempotent-Replayed') === 'true' };
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return { title: 'Unexpected response', detail: text.slice(0, 200) };
  }
}

export function describeError(e) {
  if (!(e instanceof ApiError)) return e.message || 'Network error';
  const fieldErrors = e.problem?.errors?.map((f) => `${f.field}: ${f.message}`).join('; ');
  return fieldErrors ? `${e.problem.title}: ${fieldErrors}` : `${e.problem?.title ?? 'Error'}: ${e.message}`;
}
