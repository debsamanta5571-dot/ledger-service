import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { newIdempotencyKey } from './ids.js';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test('uses randomUUID when available', () => {
  assert.equal(newIdempotencyKey({ randomUUID: () => 'from-randomUUID' }), 'from-randomUUID');
});

test('falls back to a v4 UUID from getRandomValues when randomUUID is missing (insecure context)', () => {
  const insecure = { getRandomValues: (a) => webcrypto.getRandomValues(a) };
  const a = newIdempotencyKey(insecure);
  const b = newIdempotencyKey(insecure);
  assert.match(a, UUID_V4);
  assert.notEqual(a, b);
});
