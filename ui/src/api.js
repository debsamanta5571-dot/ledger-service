// Thin fetch wrapper. Errors from the service are RFC 7807 problem+json; they are surfaced as ApiError.

const BASE = import.meta.env.VITE_API_BASE ?? '/api';

export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || problem?.title || `Request failed (${status})`);
    this.status = status;
    this.problem = problem;
  }
}

export async function api(apiKey, path, { method = 'GET', body, headers = {} } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'X-API-Key': apiKey,
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
