import { useCallback, useEffect, useRef, useState } from 'react';
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
  const [keyRejected, setKeyRejected] = useState(false);
  const latestKey = useRef(apiKey);
  latestKey.current = apiKey;

  const refresh = useCallback(async () => {
    if (!apiKey) {
      setAccounts([]);
      return;
    }
    try {
      const { data } = await api(apiKey, '/accounts?limit=100&includeClosed=true');
      if (latestKey.current !== apiKey) return; // a newer key was typed while this request was in flight
      setAccounts(data);
      setError(null);
      setKeyRejected(false);
    } catch (e) {
      if (latestKey.current !== apiKey) return;
      const rejected = e.status === 401;
      // Never keep showing data that was loaded with a key the server now rejects. Other failures (rate limit,
      // server down) keep the page as it was and just show the error.
      if (rejected) setAccounts([]);
      setKeyRejected(rejected);
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

      {apiKey && !keyRejected ? (
        <>
          <AccountsPanel apiKey={apiKey} accounts={accounts} onChanged={refresh} />
          <TransferPanel apiKey={apiKey} accounts={accounts} onChanged={refresh} />
          <StatementPanel apiKey={apiKey} accounts={accounts} />
        </>
      ) : (
        <p>{apiKey ? 'Fix the API key above to continue.' : 'Enter an API key to begin.'}</p>
      )}
    </main>
  );
}
