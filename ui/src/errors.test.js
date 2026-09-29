import test from 'node:test';
import assert from 'node:assert/strict';
import { describeProblem } from './errors.js';

test('insufficient funds is shown in major units, not raw cents', () => {
  const problem = { type: 'urn:ledger:problem:insufficient-funds', title: 'Insufficient funds', available: 12000,
    detail: '12000 available (including overdraft), 500000 requested' };
  assert.equal(describeProblem(422, problem), 'Insufficient funds: only 120.00 is available, including any overdraft.');
  assert.match(describeProblem(422, { ...problem, available: 0 }), /nothing is available/);
});

test('a detail that already starts with the title is not prefixed twice', () => {
  const problem = { title: 'Invalid API key', detail: 'Invalid API key: it is not recognized or has been revoked' };
  assert.equal(describeProblem(401, problem), 'Invalid API key: it is not recognized or has been revoked');
});

test('title and detail are joined when they differ', () => {
  assert.equal(describeProblem(404, { title: 'Account not found', detail: 'Account 42 not found' }),
    'Account not found: Account 42 not found');
  assert.equal(describeProblem(409, { title: 'Conflict', detail: 'Conflict' }), 'Conflict');
});

test('validation errors list each field', () => {
  const problem = { title: 'Validation failed', detail: 'One or more fields are invalid',
    errors: [{ field: 'amount', message: 'must be greater than 0' }, { field: 'currency', message: 'must not be null' }] };
  assert.equal(describeProblem(400, problem),
    'Validation failed: amount: must be greater than 0; currency: must not be null');
});

test('no problem body falls back to the status', () => {
  assert.equal(describeProblem(502, null), 'Request failed (502)');
});
