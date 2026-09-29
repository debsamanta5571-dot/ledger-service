import { useEffect, useState } from 'react';
import { api, describeError } from './api.js';
import { formatMinor, parseMajor } from './money.js';
import { newIdempotencyKey } from './ids.js';

export default function TransferPanel({ apiKey, accounts, onChanged }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [message, setMessage] = useState(null);
  const [busy, setBusy] = useState(false);
  // One key per logical transfer. It is reused if the user resubmits an unchanged form (say, after a network
  // error), so a retry can never post twice; it is replaced as soon as any field changes or the transfer succeeds.
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);

  useEffect(() => {
    setIdempotencyKey(newIdempotencyKey());
  }, [from, to, amount, description]);

  const source = accounts.find((a) => a.id === from);

  async function submit(e) {
    e.preventDefault();
    const minor = parseMajor(amount);
    if (!source || !to || minor === null || minor === 0) {
      setMessage({ kind: 'error', text: 'Pick both accounts and enter a positive amount like 25 or 25.50' });
      return;
    }
    setBusy(true);
    try {
      const { data, replayed } = await api(apiKey, '/transfers', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: {
          fromAccountId: from,
          toAccountId: to,
          amount: minor,
          currency: source.currency,
          description: description || null,
        },
      });
      setMessage({
        kind: 'ok',
        text: `Transferred ${formatMinor(data.amount)} ${data.currency} (transaction ${data.transactionId.slice(0, 8)})${
          replayed ? ' — this was a replay of an earlier identical request' : ''
        }`,
      });
      setAmount('');
      setDescription('');
      await onChanged();
    } catch (err) {
      setMessage({ kind: 'error', text: describeError(err) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h2>Transfer</h2>
      <form onSubmit={submit} className="row">
        <select value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From account">
          <option value="">From…</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name} ({a.currency}, {formatMinor(a.balance)})
            </option>
          ))}
        </select>
        <select value={to} onChange={(e) => setTo(e.target.value)} aria-label="To account">
          <option value="">To…</option>
          {accounts
            .filter((a) => a.id !== from)
            .map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.currency})
              </option>
            ))}
        </select>
        <input
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          placeholder="Amount"
          size={8}
          inputMode="decimal"
          aria-label="Amount"
        />
        <input
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Description (optional)"
          maxLength={255}
        />
        <button disabled={busy}>Send</button>
      </form>
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
      <p className="hint mono">Idempotency-Key: {idempotencyKey}</p>
    </section>
  );
}
