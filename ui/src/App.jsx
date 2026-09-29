import { useCallback, useEffect, useState } from 'react';
import { api, describeError } from './api.js';
import AccountsPanel from './AccountsPanel.jsx';
import TransferPanel from './TransferPanel.jsx';
import StatementPanel from './StatementPanel.jsx';

const KEY_STORAGE = 'ledger.apiKey';

function loadKey() {
  try {
    return sessionStorage.getItem(KEY_STORAGE) ?? '';
  } catch {
    return '';
  }
}

export default function App() {
  const [apiKey, setApiKey] = useState(loadKey);
  const [accounts, setAccounts] = useState([]);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    if (!apiKey) return;
    try {
      const { data } = await api(apiKey, '/accounts?limit=100');
      setAccounts(data);
      setError(null);
    } catch (e) {
      setError(describeError(e));
    }
  }, [apiKey]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  function saveKey(value) {
    setApiKey(value);
    try {
      sessionStorage.setItem(KEY_STORAGE, value);
    } catch {
      /* storage unavailable: the key just will not survive a reload */
    }
  }

  return (
    <main>
      <h1>Ledger</h1>
      <section>
        <label>
          API key
          <input
            type="password"
            value={apiKey}
            onChange={(e) => saveKey(e.target.value)}
            placeholder="X-API-Key"
            autoComplete="off"
          />
        </label>
        {error && <p className="error">{error}</p>}
      </section>

      {apiKey ? (
        <>
          <AccountsPanel apiKey={apiKey} accounts={accounts} onChanged={refresh} />
          <TransferPanel apiKey={apiKey} accounts={accounts} onChanged={refresh} />
          <StatementPanel apiKey={apiKey} accounts={accounts} />
        </>
      ) : (
        <p>Enter an API key to begin.</p>
      )}
    </main>
  );
}
