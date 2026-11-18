import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../../api/client';
import type { Layout, OwnerCinema, Screen } from '../../api/types';
import { ConfirmDialog } from '../ConfirmDialog';
import { EmptyState } from '../States';
import { useToast } from '../Toasts';
import { Field, FormError } from './FormBits';
import { checkLayout, LayoutEditor, LayoutPreview, SAMPLE_LAYOUT } from './LayoutEditor';

interface Props {
  cinemas: OwnerCinema[];
  /** Where mutations go: admins use /api/admin, owners /api/owner (owners cannot delete). */
  prefix: '/api/admin' | '/api/owner';
  onChanged: () => void;
}

/** Cinemas with their screens; create a screen or edit its name and layout with a live preview. */
export function Screens({ cinemas, prefix, onChanged }: Props) {
  const [editing, setEditing] = useState<{ cinema: OwnerCinema; screen: Screen | null } | null>(null);
  const [deleting, setDeleting] = useState<Screen | null>(null);
  const toast = useToast();

  async function remove(s: Screen) {
    setDeleting(null);
    try {
      await api.del(`/api/admin/screens/${s.id}`);
      toast.show(`Screen ${s.name} deleted.`, 'success');
      onChanged();
    } catch (e) {
      toast.error(e);
    }
  }

  if (!cinemas.length) {
    return (
      <EmptyState title="No cinemas linked yet" art="seats">
        <p>An administrator links your account to a cinema. Once linked, its screens appear here.</p>
      </EmptyState>
    );
  }

  return (
    <div className="screens">
      {editing && (
        <ScreenForm
          key={`${editing.cinema.id}-${editing.screen?.id ?? 'new'}`}
          cinema={editing.cinema}
          screen={editing.screen}
          prefix={prefix}
          onDone={() => {
            setEditing(null);
            onChanged();
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {cinemas.map((c) => (
        <section key={c.id} className="panel">
          <div className="panel__head">
            <div>
              <h3>{c.name}</h3>
              <p className="muted">{c.address}</p>
            </div>
            <button type="button" className="btn btn--sm" onClick={() => setEditing({ cinema: c, screen: null })}>
              Add screen<span className="visually-hidden"> to {c.name}</span>
            </button>
          </div>
          {c.screens.length ? (
            <ul className="screen-cards" role="list">
              {c.screens.map((s) => (
                <li key={s.id} className="screen-card">
                  <LayoutPreview layout={s.layout} />
                  <div className="screen-card__foot">
                    <p>
                      <strong>{s.name}</strong> <span className="muted">{s.capacity} seats</span>
                    </p>
                    <div className="row-actions">
                      <button type="button" className="btn btn--sm btn--ghost" onClick={() => setEditing({ cinema: c, screen: s })}>
                        Edit<span className="visually-hidden"> {s.name}</span>
                      </button>
                      {prefix === '/api/admin' && (
                        <button type="button" className="btn btn--sm btn--quiet" onClick={() => setDeleting(s)}>
                          Delete<span className="visually-hidden"> {s.name}</span>
                        </button>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="muted">No screens yet.</p>
          )}
        </section>
      ))}
      {deleting && (
        <ConfirmDialog title={`Delete ${deleting.name}?`} confirmLabel="Delete screen" danger onConfirm={() => remove(deleting)} onClose={() => setDeleting(null)}>
          <p>A screen with scheduled shows cannot be deleted.</p>
        </ConfirmDialog>
      )}
    </div>
  );
}

function ScreenForm({
  cinema,
  screen,
  prefix,
  onDone,
  onCancel,
}: {
  cinema: OwnerCinema;
  screen: Screen | null;
  prefix: string;
  onDone: () => void;
  onCancel: () => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(screen?.name ?? '');
  const [text, setText] = useState(JSON.stringify(screen?.layout ?? SAMPLE_LAYOUT, null, 2));
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const check = checkLayout(text);
    if (!check.layout || check.problems.length) {
      setError(new Error('Fix the layout before saving.'));
      return;
    }
    setBusy(true);
    setError(null);
    const body = { name: name.trim(), layout: check.layout as Layout };
    try {
      if (screen) await api.put(`${prefix}/screens/${screen.id}`, body);
      else await api.post(`${prefix}/cinemas/${cinema.id}/screens`, body);
      toast.show(screen ? `Screen ${body.name} saved.` : `Screen ${body.name} added.`, 'success');
      onDone();
    } catch (err) {
      setBusy(false);
      if (err instanceof ApiError && err.status === 409) {
        setError(new Error('This screen has upcoming shows, so its layout cannot change. Rename it, or cancel those shows first.'));
      } else setError(err);
    }
  }

  return (
    <form className="panel panel--form" onSubmit={submit} aria-labelledby="screen-form-title">
      <div className="panel__head">
        <h3 id="screen-form-title">{screen ? `Edit ${screen.name}` : `New screen at ${cinema.name}`}</h3>
      </div>
      <Field id="sc-name" label="Screen name">
        <input id="sc-name" required value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <LayoutEditor value={text} onChange={setText} serverErrors={error instanceof ApiError ? error.fieldErrors : undefined} />
      <FormError error={error} />
      <div className="form-actions">
        <button type="submit" className="btn" disabled={busy}>
          {screen ? 'Save screen' : 'Add screen'}
        </button>
        <button type="button" className="btn btn--ghost" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
