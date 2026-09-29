import { useEffect, useRef, useState } from 'react';
import { api, describeError } from './api.js';
import { parseMajor } from './money.js';
import { generatePassword } from './passwords.js';

const ROLES = [
  { value: 'operator', label: 'Normal user (operator): own accounts, can send money' },
  { value: 'auditor', label: 'Read-only (auditor)' },
  { value: 'admin', label: 'Admin: every account, and user management' },
];

/**
 * "New account" pop-up. Always opens a ledger account; optionally also
 *  - creates a sign-in account for a new person in the identity service (admins holding users:admin only) and makes
 *    them the account's owner, and
 *  - generates a personal API key for the account's owner (needs an interactive sign-in).
 * Secrets (password, API key) are shown once, on the result screen, and never stored by the page.
 */
export default function NewAccountDialog({ open, onClose, auth, identity, canCreateUsers, onCreated }) {
  const dialog = useRef(null);
  const [form, setForm] = useState(initialForm);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    if (open && !d.open) {
      setForm(initialForm());
      setError(null);
      setResult(null);
      d.showModal();
    } else if (!open && d.open) {
      d.close();
    }
  }, [open]);

  const set = (field) => (e) =>
    setForm((f) => ({ ...f, [field]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }));

  const canMakeKeys = auth?.kind === 'oauth';

  async function submit(e) {
    e.preventDefault();
    const overdraftLimit = parseMajor(form.overdraft);
    if (overdraftLimit === null) return setError('Overdraft limit must be an amount like 100 or 100.50');
    if (form.withLogin && form.password.length < 12) return setError('The password needs at least 12 characters');

    setBusy(true);
    setError(null);
    const done = { steps: [] };
    try {
      // 1. The person (optional). Their identity-service id becomes the account owner "user:<id>".
      let owner = null;
      if (form.withLogin) {
        const person = await identity.createUser({
          email: form.email.trim(),
          displayName: form.displayName.trim(),
          password: form.password,
          roles: [form.role],
        });
        owner = { ownerId: `user:${person.id}`, ownerName: person.displayName };
        done.person = { email: person.email, displayName: person.displayName, role: form.role, password: form.password };
        done.steps.push(`Sign-in account created for ${person.email}`);
      }

      // 2. The ledger account, owned by that person or by you.
      const { data: account } = await api(auth, '/accounts', {
        method: 'POST',
        body: { name: form.name, currency: form.currency, type: form.type, overdraftLimit, ...(owner ?? {}) },
      });
      done.account = account;
      done.steps.push(`Account "${account.name}" opened${owner ? ` for ${owner.ownerName}` : ''}`);

      // 3. An API key for the owner (optional).
      if (form.withKey) {
        const { data: key } = await api(auth, '/api-keys', {
          method: 'POST',
          body: { name: form.keyName.trim() || `${form.name} key`, ...(owner ?? {}) },
        });
        done.key = key;
        done.steps.push(`API key "${key.name}" generated for ${key.ownerName}`);
      }
      setResult(done);
      onCreated?.();
    } catch (err) {
      // Show what already happened, so a partial success is not repeated by accident.
      setError(describeError(err));
      if (done.steps.length) setResult({ ...done, partial: true });
      onCreated?.();
    } finally {
      setBusy(false);
    }
  }

  return (
    <dialog ref={dialog} className="modal" onClose={onClose} aria-labelledby="new-account-title">
      {result ? (
        <Result result={result} error={error} onClose={onClose} />
      ) : (
        <form onSubmit={submit}>
          <h2 id="new-account-title">New account</h2>

          <fieldset>
            <legend>Account</legend>
            <label>
              Name
              <input value={form.name} onChange={set('name')} required maxLength={200} autoFocus />
            </label>
            <div className="row">
              <label>
                Currency
                <input
                  value={form.currency}
                  onChange={(e) => setForm((f) => ({ ...f, currency: e.target.value.toUpperCase() }))}
                  maxLength={3}
                  size={4}
                  required
                />
              </label>
              <label>
                Type
                <select value={form.type} onChange={set('type')}>
                  <option>ASSET</option>
                  <option>LIABILITY</option>
                </select>
              </label>
              <label>
                Overdraft limit
                <input value={form.overdraft} onChange={set('overdraft')} size={10} inputMode="decimal" />
              </label>
            </div>
          </fieldset>

          <fieldset disabled={!canCreateUsers}>
            <legend>
              <label className="check">
                <input type="checkbox" checked={form.withLogin} onChange={set('withLogin')} />
                Also create a sign-in account for a new person
              </label>
            </legend>
            {!canCreateUsers && <p className="hint">Only admins can create sign-in accounts.</p>}
            {form.withLogin && (
              <>
                <p className="hint">The new account will belong to this person, not to you.</p>
                <label>
                  Email
                  <input type="email" value={form.email} onChange={set('email')} required autoComplete="off" />
                </label>
                <label>
                  Display name
                  <input value={form.displayName} onChange={set('displayName')} required maxLength={200} />
                </label>
                <label>
                  Role
                  <select value={form.role} onChange={set('role')}>
                    {ROLES.map((r) => (
                      <option key={r.value} value={r.value}>
                        {r.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Password
                  <span className="row">
                    <input
                      type="text"
                      className="mono"
                      value={form.password}
                      onChange={set('password')}
                      minLength={12}
                      required
                      autoComplete="new-password"
                    />
                    <button type="button" onClick={() => setForm((f) => ({ ...f, password: generatePassword() }))}>
                      Generate
                    </button>
                  </span>
                </label>
              </>
            )}
          </fieldset>

          <fieldset disabled={!canMakeKeys}>
            <legend>
              <label className="check">
                <input type="checkbox" checked={form.withKey} onChange={set('withKey')} />
                Generate an API key
              </label>
            </legend>
            {!canMakeKeys && <p className="hint">Sign in (not with an API key) to generate API keys.</p>}
            {form.withKey && (
              <>
                <p className="hint">
                  For scripts. It acts as {form.withLogin ? 'the new person' : 'you'}, can use their accounts but not
                  admin powers, and is shown only once.
                </p>
                <label>
                  Key name
                  <input value={form.keyName} onChange={set('keyName')} placeholder={`${form.name || 'Account'} key`} />
                </label>
              </>
            )}
          </fieldset>

          {error && <p className="error">{error}</p>}
          <div className="row actions-row">
            <button type="button" onClick={onClose} disabled={busy}>
              Cancel
            </button>
            <button type="submit" className="primary" disabled={busy}>
              {busy ? 'Creating…' : 'Create'}
            </button>
          </div>
        </form>
      )}
    </dialog>
  );
}

function Result({ result, error, onClose }) {
  const secrets = !!(result.person || result.key);
  return (
    <div>
      <h2 id="new-account-title">{result.partial ? 'Partly done' : 'Done'}</h2>
      <ul>
        {result.steps.map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ul>
      {error && <p className="error">Stopped: {error}</p>}
      {result.person && (
        <Secret label={`Password for ${result.person.email}`} value={result.person.password} />
      )}
      {result.key && <Secret label={`API key "${result.key.name}" (send it as the X-API-Key header)`} value={result.key.key} />}
      {secrets && (
        <p className="warning">
          Copy these now. They are not stored anywhere readable and cannot be shown again (a lost API key can be revoked
          and replaced).
        </p>
      )}
      <div className="row actions-row">
        <button type="button" className="primary" onClick={onClose}>
          {secrets ? "I've saved them" : 'Close'}
        </button>
      </div>
    </div>
  );
}

function Secret({ label, value }) {
  const [copied, setCopied] = useState(false);
  return (
    <label className="secret">
      {label}
      <span className="row">
        <input readOnly value={value} className="mono" onFocus={(e) => e.target.select()} />
        <button
          type="button"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(value);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            } catch {
              /* select-and-copy by hand still works */
            }
          }}
        >
          {copied ? 'Copied!' : 'Copy'}
        </button>
      </span>
    </label>
  );
}

function initialForm() {
  return {
    name: '',
    currency: 'USD',
    type: 'ASSET',
    overdraft: '0',
    withLogin: false,
    email: '',
    displayName: '',
    role: 'operator',
    password: '',
    withKey: false,
    keyName: '',
  };
}
