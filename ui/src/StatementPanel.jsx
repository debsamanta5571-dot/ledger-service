import { useCallback, useEffect, useRef, useState } from 'react';
import { api, describeError } from './api.js';
import { formatMinor } from './money.js';

const PAGE_SIZE = 10;

export default function StatementPanel({ auth, accounts, accountId, onAccountChange }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [statement, setStatement] = useState(null);
  const [error, setError] = useState(null);
  const requestSeq = useRef(0);

  useEffect(() => {
    setPage(0);
  }, [accountId]);

  const load = useCallback(async () => {
    if (!accountId) {
      requestSeq.current++;
      setStatement(null);
      return;
    }
    const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    const seq = ++requestSeq.current;
    try {
      const { data } = await api(auth, `/accounts/${accountId}/statement?${params}`);
      if (seq !== requestSeq.current) return; // a newer request superseded this one
      setStatement(data);
      setError(null);
    } catch (e) {
      if (seq !== requestSeq.current) return;
      setStatement(null);
      setError(describeError(e));
    }
  }, [auth, accountId, from, to, page]);

  useEffect(() => {
    load();
  }, [load]);

  // Reload when balances change elsewhere (a transfer refreshes the accounts list).
  useEffect(() => {
    load();
  }, [accounts]); // eslint-disable-line react-hooks/exhaustive-deps

  function changeFilter(setter) {
    return (e) => {
      setter(e.target.value);
      setPage(0);
    };
  }

  return (
    <section id="statement">
      <h2>Statement</h2>
      <div className="row">
        <select value={accountId} onChange={changeFilter(onAccountChange)} aria-label="Account">
          <option value="">Account…</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name} ({a.currency})
            </option>
          ))}
        </select>
        <label>
          From <input type="date" value={from} onChange={changeFilter(setFrom)} />
        </label>
        <label>
          To <input type="date" value={to} onChange={changeFilter(setTo)} />
        </label>
      </div>
      {error && <p className="error">{error}</p>}

      {statement && (
        <>
          <p>
            Opening <strong>{formatMinor(statement.openingBalance)}</strong> · Closing{' '}
            <strong>{formatMinor(statement.closingBalance)}</strong> {statement.currency} ·{' '}
            {statement.totalElements} entries
          </p>
          <table>
            <thead>
              <tr>
                <th>When (UTC)</th>
                <th>Description</th>
                <th>Side</th>
                <th className="num">Amount</th>
                <th className="num">Balance after</th>
              </tr>
            </thead>
            <tbody>
              {statement.entries.map((e) => (
                <tr key={e.entryId}>
                  <td>{e.createdAt.replace('T', ' ').slice(0, 19)}</td>
                  <td>{e.description ?? ''}</td>
                  <td>{e.direction}</td>
                  <td className="num">{formatMinor(e.amount)}</td>
                  <td className={`num ${e.balanceAfter < 0 ? 'neg' : ''}`}>{formatMinor(e.balanceAfter)}</td>
                </tr>
              ))}
              {statement.entries.length === 0 && (
                <tr>
                  <td colSpan="5">No entries in this range.</td>
                </tr>
              )}
            </tbody>
          </table>
          <div className="row">
            <button disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </button>
            <span>
              Page {statement.totalPages === 0 ? 0 : page + 1} of {statement.totalPages}
            </span>
            <button disabled={page + 1 >= statement.totalPages} onClick={() => setPage(page + 1)}>
              Next
            </button>
          </div>
        </>
      )}
    </section>
  );
}
