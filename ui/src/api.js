import { describeProblem } from './errors.js';

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
 * How to authenticate: either as a signed-in user (a bearer access token from the identity service) or with an API key.
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
      if (!token) {
        throw new ApiError(401, { title: 'Signed out', detail: 'Your sign-in has expired. Please sign in again.' });
      }
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

/**
 * Calls the identity service's own admin API with the signed-in user's token (only used to create sign-in accounts,
 * which needs users:admin). Its validation errors come back as { errors: { field: [messages] } }.
 */
export function identityClient(identityUrl, auth) {
  return {
    async createUser(body) {
      const res = await fetch(`${identityUrl}/api/users`, {
        method: 'POST',
        headers: { ...(await auth.headers()), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        const fields = data?.errors && !Array.isArray(data.errors)
          ? Object.entries(data.errors).map(([field, msgs]) => ({ field, message: [].concat(msgs).join(' ') }))
          : undefined;
        const title = data?.title ?? 'Could not create the sign-in account';
        throw new ApiError(res.status, { ...data, title, errors: fields });
      }
      return data;
    },
  };
}

/** One readable sentence for any failure (see errors.js for how API problems are worded). */
export function describeError(e) {
  if (!(e instanceof ApiError)) return e.message || 'Network error';
  return describeProblem(e.status, e.problem, e.message);
}
