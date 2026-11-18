import { useEffect, useState } from 'react';
import { api, ApiError } from '../../api/client';
import type { AdminUser, OwnerCinema, Page } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Panel } from '../../components/manage/FormBits';
import { EmptyState, ErrorState, LoadingBlock, Skeleton } from '../../components/States';
import { useToast } from '../../components/Toasts';
import { useAsync } from '../../hooks/useAsync';

type GrantRole = 'THEATRE_OWNER' | 'ADMIN';
const ROLE_LABEL: Record<string, string> = { AUDIENCE: 'Audience', USER: 'Audience', THEATRE_OWNER: 'Theatre owner', ADMIN: 'Admin' };

/** Search accounts, grant or revoke THEATRE_OWNER and ADMIN, and link owners to cinemas. */
export function Users({ cinemas, onChanged }: { cinemas: OwnerCinema[]; onChanged: () => void }) {
  const { user: me } = useAuth();
  const toast = useToast();
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [confirm, setConfirm] = useState<{ user: AdminUser; role: GrantRole; grant: boolean } | null>(null);
  const { data, error, loading, reload, setData } = useAsync(
    (signal) => api.get<Page<AdminUser>>('/api/admin/users', { q: query || undefined, page, size: 20 }, signal),
    [query, page],
  );

  useEffect(() => {
    const t = window.setTimeout(() => {
      setQuery(q.trim());
      setPage(1);
    }, 300);
    return () => window.clearTimeout(t);
  }, [q]);

  const replace = (u: AdminUser) => setData((p) => (p ? { ...p, items: p.items.map((x) => (x.id === u.id ? u : x)) } : p));

  async function setRole(u: AdminUser, role: GrantRole, grant: boolean) {
    setConfirm(null);
    try {
      const out = grant
        ? await api.put<AdminUser>(`/api/admin/users/${u.id}/roles/${role}`, {})
        : await api.del<AdminUser>(`/api/admin/users/${u.id}/roles/${role}`);
      replace(out);
      toast.show(`${u.name} ${grant ? 'is now' : 'is no longer'} ${role === 'ADMIN' ? 'an admin' : 'a theatre owner'}.`, 'success');
      if (role === 'THEATRE_OWNER') onChanged();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && role === 'ADMIN') {
        toast.show('You cannot remove your own admin role. Ask another admin to do it.', 'error', e.requestId ? `Reference ${e.requestId}` : undefined);
      } else toast.error(e);
    }
  }

  async function link(u: AdminUser, cinemaId: number, on: boolean) {
    try {
      const out = on
        ? await api.put<AdminUser>(`/api/admin/cinemas/${cinemaId}/owners/${u.id}`, {})
        : await api.del<AdminUser>(`/api/admin/cinemas/${cinemaId}/owners/${u.id}`);
      // Unlink returns the user too; fall back to a local update if the body is empty.
      replace(out ?? { ...u, cinemaIds: u.cinemaIds.filter((c) => c !== cinemaId) });
      const name = cinemas.find((c) => c.id === cinemaId)?.name ?? `cinema ${cinemaId}`;
      toast.show(on ? `${u.name} now manages ${name}.` : `${u.name} no longer manages ${name}.`, 'success');
    } catch (e) {
      toast.error(e);
    }
  }

  return (
    <Panel title="Users">
      <label className="search">
        <span className="visually-hidden">Search users by name or email</span>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="6.5" />
          <path d="M16 16l5 5" />
        </svg>
        <input type="search" placeholder="Search by name or email" value={q} onChange={(e) => setQ(e.target.value)} />
      </label>
      {loading && (
        <LoadingBlock label="Loading users">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="skeleton--booking" />
          ))}
        </LoadingBlock>
      )}
      {error && <ErrorState error={error} onRetry={reload} />}
      {data && !loading && !data.items.length && <EmptyState title="No one matches that search" art="seats" />}
      {data && !loading && !!data.items.length && (
        <ul className="user-list" role="list">
          {data.items.map((u) => {
            const owner = u.roles.includes('THEATRE_OWNER');
            const admin = u.roles.includes('ADMIN');
            const self = me?.id === u.id;
            return (
              <li key={u.id} className="user-row">
                <div className="user-row__who">
                  <span className="avatar" aria-hidden="true">
                    {u.name.slice(0, 1).toUpperCase()}
                  </span>
                  <div>
                    <p className="user-row__name">
                      {u.name} {self && <span className="muted">(you)</span>}
                    </p>
                    <p className="muted">{u.email}</p>
                    <p className="role-tags">
                      {u.roles.map((r) => (
                        <span key={r} className={`tag tag--role-${r.toLowerCase()}`}>
                          {ROLE_LABEL[r] ?? r}
                        </span>
                      ))}
                    </p>
                  </div>
                </div>
                <div className="user-row__actions">
                  <button type="button" className={`btn btn--sm${owner ? ' btn--quiet' : ''}`} onClick={() => setConfirm({ user: u, role: 'THEATRE_OWNER', grant: !owner })}>
                    {owner ? 'Revoke owner' : 'Make owner'}
                    <span className="visually-hidden"> for {u.name}</span>
                  </button>
                  <button
                    type="button"
                    className={`btn btn--sm ${admin ? 'btn--quiet' : 'btn--ghost'}`}
                    onClick={() => setConfirm({ user: u, role: 'ADMIN', grant: !admin })}
                  >
                    {admin ? 'Revoke admin' : 'Make admin'}
                    <span className="visually-hidden"> for {u.name}</span>
                  </button>
                </div>
                {owner && (
                  <fieldset className="chips user-row__cinemas">
                    <legend>Cinemas {u.name} manages</legend>
                    {cinemas.map((c) => {
                      const on = u.cinemaIds.includes(c.id);
                      return (
                        <button key={c.id} type="button" className="chip" aria-pressed={on} onClick={() => link(u, c.id, !on)}>
                          {on && <span aria-hidden="true">✓ </span>}
                          {c.name}
                        </button>
                      );
                    })}
                  </fieldset>
                )}
                {!owner && (
                  <details className="user-row__assign">
                    <summary>Assign a cinema</summary>
                    <p className="muted">Assigning a cinema also makes {u.name} a theatre owner.</p>
                    <div className="chips">
                      {cinemas.map((c) => (
                        <button key={c.id} type="button" className="chip" aria-pressed={false} onClick={() => link(u, c.id, true)}>
                          {c.name}
                        </button>
                      ))}
                    </div>
                  </details>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {data && data.totalPages > 1 && (
        <nav className="pager" aria-label="User pages">
          <button type="button" className="btn btn--ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            Previous
          </button>
          <span>
            Page {data.page} of {data.totalPages}
          </span>
          <button type="button" className="btn btn--ghost" disabled={page >= data.totalPages} onClick={() => setPage(page + 1)}>
            Next
          </button>
        </nav>
      )}
      {confirm && (
        <ConfirmDialog
          title={`${confirm.grant ? 'Make' : 'Remove'} ${confirm.user.name} ${confirm.grant ? 'a' : 'as'} ${ROLE_LABEL[confirm.role]?.toLowerCase()}?`}
          confirmLabel={confirm.grant ? 'Grant role' : 'Revoke role'}
          danger={!confirm.grant}
          onConfirm={() => setRole(confirm.user, confirm.role, confirm.grant)}
          onClose={() => setConfirm(null)}
        >
          <p>
            {confirm.role === 'THEATRE_OWNER'
              ? confirm.grant
                ? 'They can manage screens, shows and reports for the cinemas you link them to.'
                : 'They lose access to the owner dashboard and every cinema link is removed.'
              : confirm.grant
                ? 'They get full access to every admin tool, including roles.'
                : 'They lose access to the admin area.'}{' '}
            The change applies when they next sign in or their session refreshes, within 15 minutes.
          </p>
        </ConfirmDialog>
      )}
    </Panel>
  );
}
