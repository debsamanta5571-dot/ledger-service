import { useState } from 'react';
import { api, describeError } from './api.js';
import { formatMinor, parseMajor } from './money.js';

export default function AccountsPanel({ apiKey, accounts, onChanged }) {
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [type, setType] = useState('ASSET');
  const [overdraft, setOverdraft] = useState('0');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [closing, setClosing] = useState(null);

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

  async function close(account) {
    if (!window.confirm(`Close "${account.name}"? It stays in the records but can no longer send or receive money.`)) {
      return;
    }
    setClosing(account.id);
    try {
      await api(apiKey, `/accounts/${account.id}`, { method: 'DELETE' });
      setError(null);
      await onChanged();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setClosing(null);
    }
  }

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
              <td>
                {!a.closedAt && (
                  <button
                    type="button"
                    onClick={() => close(a)}
                    disabled={a.balance !== 0 || closing === a.id}
                    title={a.balance !== 0 ? 'Only an account with a zero balance can be closed' : 'Close this account'}
                  >
                    Close
                  </button>
                )}
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
