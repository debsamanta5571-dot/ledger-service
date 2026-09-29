import test from 'node:test';
import assert from 'node:assert/strict';
import { accountLabel } from './labels.js';

const acct = { name: 'Savings', currency: 'USD', ownerName: 'Ada' };

test('own-account label has no owner', () => {
  assert.equal(accountLabel(acct), 'Savings (USD)');
  assert.equal(accountLabel(acct, { withBalance: '10.00' }), 'Savings (USD, 10.00)');
});

test("admin label names the owner, so different customers' accounts are distinguishable", () => {
  assert.equal(accountLabel(acct, { showOwner: true }), 'Savings · Ada (USD)');
  assert.equal(accountLabel({ ...acct, ownerName: null }, { showOwner: true }), 'Savings (USD)');
});
