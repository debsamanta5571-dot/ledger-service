import { useEffect, useRef, useState } from 'react';
import { api, describeError } from './api.js';
import { formatMinor } from './money.js';
import NewAccountDialog from './NewAccountDialog.jsx';

export default function AccountsPanel({ auth, identity, canCreateUsers, isAdmin, me, accounts, onChanged, onViewStatement }) {
  const [error, setError] = useState(null);
  const [pending, setPending] = useState(null);
  const [dialogOpen, setDialogOpen] = useState(false);

  async function run(account, { confirmText, path }) {
    if (!window.confirm(confirmText)) return;
    setPending(account.id);
    try {
      await api(auth, path, { method: 'DELETE' });
      setError(null);
      await onChanged();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setPending(null);
    }
  }

  const close = (a) =>
    run(a, {
      confirmText: `Close "${a.name}"? It stays in the records but can no longer send or receive money.`,
      path: `/accounts/${a.id}`,
    });

  const deleteForGood = (a) =>
    run(a, {
      confirmText: `Permanently delete "${a.name}"? This cannot be undone.`,
      path: `/accounts/${a.id}?permanent=true`,
    });

  return (
    <section>
      <div className="section-head">
        <h2>Accounts</h2>
        <button type="button" className="primary" onClick={() => setDialogOpen(true)}>
          + New account
        </button>
      </div>
      <NewAccountDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        auth={auth}
        identity={identity}
        canCreateUsers={canCreateUsers}
        onCreated={onChanged}
      />
      {error && <p className="error">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>Name</th>
            {isAdmin && <th>Owner</th>}
            <th>Type</th>
            <th>Currency</th>
            <th className="num">Overdraft</th>
            <th className="num">Balance</th>
            <th>Id</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {accounts.map((a) => (
            <tr key={a.id} className={a.closedAt ? 'closed' : ''}>
              <td>
                {a.name}
                {a.closedAt && <span className="badge">closed</span>}
              </td>
              {isAdmin && <td>{a.ownerId === me ? 'You' : (a.ownerName ?? a.ownerId)}</td>}
              <td>{a.type}</td>
              <td>{a.currency}</td>
              <td className="num">{formatMinor(a.overdraftLimit)}</td>
              <td className={`num ${a.balance < 0 ? 'neg' : ''}`}>{formatMinor(a.balance)}</td>
              <td className="mono">{a.id.slice(0, 8)}</td>
              <td className="row-actions">
                <AccountActions
                  account={a}
                  busy={pending === a.id}
                  onViewStatement={() => onViewStatement(a.id)}
                  onClose={() => close(a)}
                  onDelete={() => deleteForGood(a)}
                />
              </td>
            </tr>
          ))}
          {accounts.length === 0 && (
            <tr>
              <td colSpan={isAdmin ? 8 : 7}>No accounts yet.</td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}

/**
 * Per-account "Actions" menu. Every option is always listed; one that does not apply is disabled with the reason
 * next to it, so the rules (zero balance to close, no history to delete) are visible rather than surprising.
 */
function AccountActions({ account, busy, onViewStatement, onClose, onDelete }) {
  const closeBlocked = account.closedAt
    ? 'already closed'
    : account.balance !== 0
      ? 'balance must be zero'
      : null;
  const deleteBlocked =
    account.entryCount > 0 ? `has ${account.entryCount} ledger entries, which are kept forever` : null;

  const ref = useRef(null);
  const [copied, setCopied] = useState(false);

  async function copyId() {
    try {
      await navigator.clipboard.writeText(account.id);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard needs a secure context (localhost counts); fall back to showing the id.
      window.prompt('Account ID', account.id);
    }
  }
  // <details> does not close itself on an outside click or Escape; do that here.
  useEffect(() => {
    const closeUnlessInside = (e) => {
      if (ref.current?.open && !ref.current.contains(e.target)) ref.current.removeAttribute('open');
    };
    const closeOnEscape = (e) => {
      if (e.key === 'Escape') ref.current?.removeAttribute('open');
    };
    document.addEventListener('click', closeUnlessInside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('click', closeUnlessInside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, []);

  // <details> gives an accessible expand/collapse without extra dependencies; close it after picking an option.
  const pick = (action) => (e) => {
    e.currentTarget.closest('details')?.removeAttribute('open');
    action();
  };

  return (
    <details className="actions" ref={ref}>
      <summary aria-label={`Actions for ${account.name}`}>{busy ? 'Working…' : 'Actions'}</summary>
      <div className="menu" role="menu">
        <button type="button" role="menuitem" onClick={pick(onViewStatement)}>
          View statements
        </button>
        <button type="button" role="menuitem" onClick={copyId}>
          {copied ? 'Copied!' : 'Copy account ID'}
          <small>share it so others can pay into this account</small>
        </button>
        <button type="button" role="menuitem" onClick={pick(onClose)} disabled={busy || !!closeBlocked}>
          Close account
          {closeBlocked && <small>{closeBlocked}</small>}
        </button>
        <button
          type="button"
          role="menuitem"
          className="danger"
          onClick={pick(onDelete)}
          disabled={busy || !!deleteBlocked}
        >
          Delete permanently
          {deleteBlocked && <small>{deleteBlocked}</small>}
        </button>
      </div>
    </details>
  );
}
