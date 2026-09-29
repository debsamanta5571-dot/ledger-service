/** "Savings (USD)" for your own account; "Savings · Ada (USD)" when an admin sees someone else's. */
export function accountLabel(account, { showOwner = false, withBalance = null } = {}) {
  const owner = showOwner && account.ownerName ? ` · ${account.ownerName}` : '';
  const extra = withBalance === null ? account.currency : `${account.currency}, ${withBalance}`;
  return `${account.name}${owner} (${extra})`;
}
