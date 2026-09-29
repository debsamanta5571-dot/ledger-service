import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, apiKeyCredential, describeError, identityClient, oauthCredential, publicGet } from './api.js';
import { isCallback, OAuthSession } from './auth.js';
import AccountsPanel from './AccountsPanel.jsx';
import TransferPanel from './TransferPanel.jsx';
import StatementPanel from './StatementPanel.jsx';

const KEY_STORAGE = 'ledger.apiKey';
// Remembers "this tab is signed in" across a reload. Tokens themselves are never stored (see auth.js): after a reload
// the page re-runs the sign-in redirect, which the identity service answers instantly from its own session.
const MODE_STORAGE = 'ledger.mode';

function load(key) {
  try {
    return sessionStorage.getItem(key) ?? '';
  } catch {
    return '';
  }
}

function save(key, value) {
  try {
    if (value) sessionStorage.setItem(key, value);
    else sessionStorage.removeItem(key);
  } catch {
    /* storage unavailable: the choice just will not survive a reload */
  }
}

export default function App() {
  const [config, setConfig] = useState(null); // identity-service sign-in settings, from GET /ui-config
  const [session, setSession] = useState(null); // OAuthSession once a user is signed in
  const [user, setUser] = useState(null);
  const [signingIn, setSigningIn] = useState(false);
  const [apiKey, setApiKey] = useState(() => load(KEY_STORAGE));
  const [useApiKey, setUseApiKey] = useState(() => load(MODE_STORAGE) === 'apiKey');
  const [accounts, setAccounts] = useState([]);
  const [error, setError] = useState(null);
  const [rejected, setRejected] = useState(false);
  const [statementAccountId, setStatementAccountId] = useState('');
  const [onlyMine, setOnlyMine] = useState(false); // admins: narrow the list to their own accounts

  // Start-up: load the sign-in settings, then either finish a sign-in (we are the redirect target) or resume one.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let cfg;
      try {
        cfg = await publicGet('/ui-config');
        if (!cancelled) setConfig(cfg);
      } catch {
        return; // sign-in unavailable (older server); API keys still work
      }
      if (isCallback(window.location.search)) {
        const s = new OAuthSession(cfg);
        try {
          setSigningIn(true);
          await s.handleCallback(window.location.search);
          if (cancelled) return;
          save(MODE_STORAGE, 'oauth');
          setUseApiKey(false);
          setSession(s);
          setUser(s.user);
        } catch (e) {
          save(MODE_STORAGE, ''); // never loop straight back into a failing sign-in
          if (!cancelled) setError(e.message);
        } finally {
          window.history.replaceState(null, '', window.location.pathname); // drop ?code=&state= from the URL
          if (!cancelled) setSigningIn(false);
        }
      } else if (load(MODE_STORAGE) === 'oauth') {
        setSigningIn(true);
        await new OAuthSession(cfg).login(); // reload: silently re-authorize via the identity session
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // The credential every request uses. Memoized, so it changes only when the signed-in identity changes.
  const auth = useMemo(() => {
    if (session && user) return oauthCredential(session);
    if (useApiKey && apiKey) return apiKeyCredential(apiKey);
    return null;
  }, [session, user, useApiKey, apiKey]);

  const latestAuth = useRef(auth);
  latestAuth.current = auth;

  const refresh = useCallback(async () => {
    if (!auth) {
      setAccounts([]);
      return;
    }
    try {
      const { data } = await api(auth, `/accounts?limit=100&includeClosed=true${onlyMine ? '&mine=true' : ''}`);
      if (latestAuth.current !== auth) return; // who-you-are changed while this was in flight
      setAccounts(data);
      setError(null);
      setRejected(false);
    } catch (e) {
      if (latestAuth.current !== auth) return;
      const isRejected = e.status === 401;
      // Never keep showing data loaded with credentials that the server now rejects. Other failures (a rate limit,
      // the server being down) leave the page as it was and just show the error.
      if (isRejected) setAccounts([]);
      setRejected(isRejected);
      if (isRejected && auth.kind === 'oauth') {
        session?.clear();
        setSession(null);
        setUser(null);
        save(MODE_STORAGE, '');
      }
      setError(describeError(e));
    }
  }, [auth, session, onlyMine]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function signIn() {
    setError(null);
    setSigningIn(true);
    // Deliberately NOT remembering "signed in" yet: that happens only after the callback succeeds. Remembering it
    // here made every later visit bounce straight back to the login page if the user abandoned it.
    try {
      await new OAuthSession(config).login();
    } catch (e) {
      setSigningIn(false);
      setError(e.message);
    }
  }

  async function signOut() {
    save(MODE_STORAGE, '');
    setAccounts([]);
    const s = session;
    setSession(null);
    setUser(null);
    await s?.logout(); // revokes the refresh token and ends the identity-service session
  }

  function chooseApiKey(on) {
    setUseApiKey(on);
    save(MODE_STORAGE, on ? 'apiKey' : '');
    setError(null);
    setRejected(false);
  }

  function saveKey(value) {
    setApiKey(value);
    save(KEY_STORAGE, value);
  }

  const panels = auth && !rejected;
  // Admins (ledger:admin, granted only to the identity service's admin role) see and act on every account. The
  // server enforces this; the page only adapts its display.
  const isAdmin = !!user?.scopes.includes('ledger:admin');
  // Creating sign-in accounts is the identity service's users:admin power (admins only), called directly from here.
  const canCreateUsers = !!user?.scopes.includes('users:admin');
  const identity = useMemo(
    () => (config && auth?.kind === 'oauth' ? identityClient(config.identityUrl, auth) : null),
    [config, auth],
  );

  return (
    <main>
      <header className="topbar">
        <h1>Ledger</h1>
        {user && (
          <div className="who">
            Signed in as <strong>{user.name}</strong>
            {isAdmin && <span className="badge admin">Admin</span>}
            <button type="button" onClick={signOut}>
              Sign out
            </button>
          </div>
        )}
      </header>

      {!user && (
        <section className="signin">
          {signingIn ? (
            <p>Signing you in…</p>
          ) : useApiKey ? (
            <>
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
              {config && (
                <button type="button" className="link" onClick={() => chooseApiKey(false)}>
                  Sign in with your account instead
                </button>
              )}
            </>
          ) : (
            <>
              <button type="button" className="primary" onClick={signIn} disabled={!config}>
                Sign in
              </button>
              {!config && <p className="hint">Sign-in is not configured on this server; use an API key.</p>}
              <button type="button" className="link" onClick={() => chooseApiKey(true)}>
                Use an API key instead
              </button>
            </>
          )}
        </section>
      )}

      {user && !user.scopes.includes('accounts:write') && (
        <p className="hint">
          Your role allows: {user.scopes.filter((s) => s.includes(':')).join(', ') || 'no ledger access'}.
          Actions outside that will be refused.
        </p>
      )}
      {error && <p className="error">{error}</p>}

      {panels && isAdmin && (
        <div className="row admin-bar">
          <span>You are an admin: you can see and manage every customer's accounts.</span>
          <label>
            <input type="checkbox" checked={onlyMine} onChange={(e) => setOnlyMine(e.target.checked)} /> Only mine
          </label>
        </div>
      )}

      {panels ? (
        <>
          <AccountsPanel
            auth={auth}
            identity={identity}
            canCreateUsers={canCreateUsers}
            isAdmin={isAdmin}
            me={user?.sub ? `user:${user.sub}` : null}
            accounts={accounts}
            onChanged={refresh}
            onViewStatement={(id) => {
              setStatementAccountId(id);
              document.getElementById('statement')?.scrollIntoView({ behavior: 'smooth' });
            }}
          />
          <TransferPanel auth={auth} accounts={accounts} onChanged={refresh} isAdmin={isAdmin} />
          <StatementPanel
            auth={auth}
            isAdmin={isAdmin}
            accounts={accounts}
            accountId={statementAccountId}
            onAccountChange={setStatementAccountId}
          />
        </>
      ) : (
        !signingIn &&
        useApiKey && <p>{apiKey ? 'Fix the API key above to continue.' : 'Enter an API key to begin.'}</p>
      )}
    </main>
  );
}
