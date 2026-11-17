import type { ReactNode } from 'react';
import { ApiError } from '../../api/client';

export function Field({ id, label, children, hint, error }: { id: string; label: string; children: ReactNode; hint?: string; error?: string }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <p className="field__hint">{hint}</p>}
      {error && <p className="field-error">{error}</p>}
    </div>
  );
}

/** Problem details for a failed form submit: the message, field errors by path and the reference. */
export function FormError({ error }: { error: unknown }) {
  if (!error) return null;
  const e = error instanceof ApiError ? error : null;
  const fields = e ? Object.entries(e.fieldErrors) : [];
  return (
    <div className="alert" role="alert">
      <p>{error instanceof Error ? error.message : String(error)}</p>
      {!!fields.length && (
        <ul>
          {fields.map(([k, v]) => (
            <li key={k}>
              <code>{k}</code>: {v}
            </li>
          ))}
        </ul>
      )}
      {e?.requestId && <p className="state__ref">Reference {e.requestId}</p>}
    </div>
  );
}

export function Panel({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="panel">
      <div className="panel__head">
        <h2>{title}</h2>
        {actions}
      </div>
      {children}
    </section>
  );
}
