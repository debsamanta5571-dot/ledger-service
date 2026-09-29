import test from 'node:test';
import assert from 'node:assert/strict';
import { formatMinor, parseMajor } from './money.js';

test('formatMinor formats cents', () => {
  assert.equal(formatMinor(0), '0.00');
  assert.equal(formatMinor(5), '0.05');
  assert.equal(formatMinor(12345), '123.45');
  assert.equal(formatMinor(-5), '-0.05');
  assert.equal(formatMinor(-250000), '-2500.00');
});

test('parseMajor converts decimal strings to minor units', () => {
  assert.equal(parseMajor('12'), 1200);
  assert.equal(parseMajor('12.5'), 1250);
  assert.equal(parseMajor('12.05'), 1205);
  assert.equal(parseMajor('0.01'), 1);
  assert.equal(parseMajor(' 7.10 '), 710);
});

test('parseMajor rejects anything that is not a plain non-negative amount', () => {
  for (const bad of ['', 'abc', '-1', '1.234', '1,50', '1e3', '.5', '5.']) {
    assert.equal(parseMajor(bad), null, `expected null for ${JSON.stringify(bad)}`);
  }
});
