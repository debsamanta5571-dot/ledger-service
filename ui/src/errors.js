import { formatMinor } from './money.js';

/**
 * Turns an API failure into one readable sentence. The service answers with RFC 7807 problem details; the ones a
 * user can act on get a tailored message, and the rest read "Title: detail", without repeating a title that the
 * detail already starts with.
 */
export function describeProblem(status, problem, fallback) {
  if (!problem) return fallback || `Request failed (${status})`;
  const title = problem.title ?? 'Error';

  if (problem.type === 'urn:ledger:problem:insufficient-funds' && typeof problem.available === 'number') {
    // The API speaks in minor units (cents); the page shows amounts in major units, so say it the same way.
    return problem.available > 0
      ? `Insufficient funds: only ${formatMinor(problem.available)} is available, including any overdraft.`
      : 'Insufficient funds: nothing is available to send from this account.';
  }

  const fieldErrors = Array.isArray(problem.errors)
    ? problem.errors.map((f) => `${f.field}: ${f.message}`).join('; ')
    : '';
  if (fieldErrors) return `${title}: ${fieldErrors}`;

  const detail = problem.detail ?? fallback ?? '';
  if (!detail || detail === title) return title;
  return detail.toLowerCase().startsWith(title.toLowerCase()) ? detail : `${title}: ${detail}`;
}
