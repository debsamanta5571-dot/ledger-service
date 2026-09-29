import test from 'node:test';
import assert from 'node:assert/strict';
import { generatePassword } from './passwords.js';

test('passwords are 20 characters from the unambiguous alphabet and meet the 12-character minimum', () => {
  const p = generatePassword();
  assert.equal(p.length, 20);
  assert.match(p, /^[A-HJ-NP-Za-km-np-z2-9]+$/);
  assert.ok(!/[0O1lI]/.test(p));
});

test('passwords are not repeated', () => {
  const seen = new Set(Array.from({ length: 200 }, () => generatePassword()));
  assert.equal(seen.size, 200);
});

test('rejection sampling: bytes at or above the cutoff are skipped, not folded into the alphabet', () => {
  // 255 is above the cutoff (220 for a 55-symbol alphabet) and must be ignored; 0 maps to "A".
  let call = 0;
  const fake = { getRandomValues: (a) => a.fill(call++ === 0 ? 255 : 0) };
  assert.equal(generatePassword(4, fake), 'AAAA');
});
