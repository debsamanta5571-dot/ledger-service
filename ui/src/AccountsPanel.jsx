import { useEffect, useRef, useState } from 'react';
import { api, describeError } from './api.js';
import { formatMinor, parseMajor } from './money.js';

export default function AccountsPanel({ apiKey, accounts, onChanged, onViewStatement }) {
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [type, setType] = useState('ASSET');
  const [overdraft, setOverdraft] = useState('0');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(null);

  async function create(e) {
    e.preventDefault();
    const overdraftLimit = parseMajor(overdraft);
    if (overdraftLimit === null) {
      setError('Overdraft limit must be an amount like 100 or 100.50');
      return;
    }
    setBusy(true);
    try {
      await api(apiKey, '/accounts', { method: 'POST', body: { name, currency, type, overdraftLimit } });
      setName('');
      setError(null);
      await onChanged();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function run(account, { confirmText, path }) {
    if (!window.confirm(confirmText)) return;
    setPending(account.id);
    try {
      await api(apiKey, path, { method: 'DELETE' });
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
      <h2>Accounts</h2>
      <form onSubmit={create} className="row">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name" required />
        <input
          value={currency}
          onChange={(e) => setCurrency(e.target.value.toUpperCase())}
          maxLength={3}
          size={4}
          aria-label="Currency"
          required
        />
        <select value={type} onChange={(e) => setType(e.target.value)} aria-label="Type">
          <option>ASSET</option>
          <option>LIABILITY</option>
        </select>
        <input
          value={overdraft}
          onChange={(e) => setOverdraft(e.target.value)}
          size={8}
          aria-label="Overdraft limit"
          title="Overdraft limit"
        />
        <button disabled={busy}>Create</button>
      </form>
      {error && <p className="error">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>Name</th>
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
              <td colSpan="7">No accounts yet.</td>
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
          View statement
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
