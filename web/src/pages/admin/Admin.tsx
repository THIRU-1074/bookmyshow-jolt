import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { Cinema, City, MovieSummary, OwnerCinema, Page, Stats } from '../../api/types';
import { PageTitle } from '../../components/bits';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Field, FormError, Panel } from '../../components/manage/FormBits';
import { Reports } from '../../components/manage/Reports';
import { Screens } from '../../components/manage/Screens';
import { Shows } from '../../components/manage/Shows';
import { Tabs } from '../../components/manage/Tabs';
import { ErrorState, LoadingBlock, Skeleton } from '../../components/States';
import { useToast } from '../../components/Toasts';
import { useAsync } from '../../hooks/useAsync';
import { CERTIFICATES, GENRES, LANGUAGES, runtime } from '../../lib/format';
import { Users } from './Users';

export function Admin() {
  const cities = useAsync((signal) => api.get<City[]>('/api/cities', undefined, signal), []);
  // Admins pass every cinema scope, so the owner listing doubles as "cinemas with screen layouts".
  const cinemas = useAsync((signal) => api.get<OwnerCinema[]>('/api/owner/cinemas', undefined, signal), []);
  const stats = useAsync((signal) => api.get<Stats>('/api/admin/stats', undefined, signal), []);

  const needData = (render: (c: City[], cin: OwnerCinema[]) => JSX.Element) => () => {
    if (cities.error || cinemas.error) return <ErrorState error={cities.error ?? cinemas.error} onRetry={() => (cities.reload(), cinemas.reload())} />;
    if (!cities.data || !cinemas.data)
      return (
        <LoadingBlock>
          <Skeleton className="skeleton--card" />
        </LoadingBlock>
      );
    return render(cities.data, cinemas.data);
  };

  return (
    <div className="wrap section">
      <div className="dash-head">
        <PageTitle>Admin</PageTitle>
        {stats.data && (
          <dl className="pulse" aria-label="Right now">
            <div>
              <dt>Active holds</dt>
              <dd>{stats.data.activeHolds}</dd>
            </div>
            <div>
              <dt>Confirmed today</dt>
              <dd>{stats.data.bookingsConfirmedToday}</dd>
            </div>
            <div>
              <dt>Live viewers</dt>
              <dd>{stats.data.sseStreams}</dd>
            </div>
          </dl>
        )}
      </div>
      <Tabs
        label="Admin sections"
        tabs={[
          { key: 'movies', label: 'Films', render: () => <MoviesTab /> },
          { key: 'cities', label: 'Cities', render: () => <CitiesTab cities={cities.data ?? []} onChanged={cities.reload} /> },
          {
            key: 'cinemas',
            label: 'Cinemas',
            render: needData((c) => <CinemasTab cities={c} onChanged={cinemas.reload} />),
          },
          {
            key: 'screens',
            label: 'Screens',
            render: needData((_, cin) => <Screens cinemas={cin} prefix="/api/admin" onChanged={cinemas.reload} />),
          },
          { key: 'shows', label: 'Shows', render: needData((c, cin) => <Shows prefix="/api/admin" cinemas={cin} cities={c} />) },
          { key: 'reports', label: 'Reports', render: () => <Reports prefix="/api/admin" cities={cities.data} /> },
          {
            key: 'users',
            label: 'Users',
            render: needData((_, cin) => <Users cinemas={cin} onChanged={cinemas.reload} />),
          },
        ]}
      />
    </div>
  );
}

function MoviesTab() {
  const toast = useToast();
  const list = useAsync((signal) => api.get<Page<MovieSummary>>('/api/movies', { size: 100, sort: 'title' }, signal), []);
  const [error, setError] = useState<unknown>(null);
  const [langs, setLangs] = useState<string[]>(['en']);
  const [genres, setGenres] = useState<string[]>(['Drama']);
  const [deleting, setDeleting] = useState<MovieSummary | null>(null);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const d = Object.fromEntries(new FormData(form)) as Record<string, string>;
    setError(null);
    try {
      await api.post('/api/admin/movies', {
        title: d.title,
        certificate: d.certificate,
        durationMinutes: Number(d.duration),
        releaseDate: d.release,
        languages: langs,
        genres,
        posterUrl: d.poster || null,
        description: d.description || null,
      });
      toast.show(`${d.title} added.`, 'success');
      form.reset();
      list.reload();
    } catch (err) {
      setError(err);
    }
  }

  async function remove(m: MovieSummary) {
    setDeleting(null);
    try {
      await api.del(`/api/admin/movies/${m.id}`);
      toast.show(`${m.title} deleted.`, 'success');
      list.reload();
    } catch (err) {
      toast.error(err);
    }
  }

  const checks = (legend: string, all: string[], value: string[], set: (v: string[]) => void, label = (v: string) => v) => (
    <fieldset className="chips">
      <legend>{legend}</legend>
      {all.map((v) => (
        <button
          key={v}
          type="button"
          className="chip"
          aria-pressed={value.includes(v)}
          onClick={() => set(value.includes(v) ? value.filter((x) => x !== v) : [...value, v])}
        >
          {label(v)}
        </button>
      ))}
    </fieldset>
  );

  return (
    <div className="split">
      <Panel title={list.data ? `Films (${list.data.total})` : 'Films'}>
        {list.error && <ErrorState error={list.error} onRetry={list.reload} />}
        {list.loading && <Skeleton className="skeleton--card" />}
        {list.data && (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Title</th>
                  <th scope="col">Cert</th>
                  <th scope="col">Runtime</th>
                  <th scope="col">Languages</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.data.items.map((m) => (
                  <tr key={m.id}>
                    <td>{m.title}</td>
                    <td>{m.certificate}</td>
                    <td>{runtime(m.durationMinutes)}</td>
                    <td>{m.languages.join(', ')}</td>
                    <td>
                      <button type="button" className="btn btn--sm btn--quiet" onClick={() => setDeleting(m)}>
                        Delete<span className="visually-hidden"> {m.title}</span>
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      <Panel title="Add a film">
        <form className="form" onSubmit={submit}>
          <Field id="m-title" label="Title">
            <input id="m-title" name="title" required maxLength={120} />
          </Field>
          <div className="form-row">
            <Field id="m-cert" label="Certificate">
              <select id="m-cert" name="certificate">
                {CERTIFICATES.map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
            </Field>
            <Field id="m-dur" label="Minutes">
              <input id="m-dur" name="duration" type="number" min={1} max={400} required />
            </Field>
            <Field id="m-rel" label="Release date">
              <input id="m-rel" name="release" type="date" required />
            </Field>
          </div>
          {checks('Languages', Object.keys(LANGUAGES), langs, setLangs, (c) => LANGUAGES[c] ?? c)}
          {checks('Genres', GENRES, genres, setGenres)}
          <Field id="m-poster" label="Poster URL (optional)" hint="An https image. Without one, poster art is generated from the title.">
            <input id="m-poster" name="poster" type="url" placeholder="https://" />
          </Field>
          <Field id="m-desc" label="Description">
            <textarea id="m-desc" name="description" maxLength={2000} rows={3} />
          </Field>
          <FormError error={error} />
          <button type="submit" className="btn">
            Add film
          </button>
        </form>
      </Panel>
      {deleting && (
        <ConfirmDialog title={`Delete ${deleting.title}?`} confirmLabel="Delete film" danger onConfirm={() => remove(deleting)} onClose={() => setDeleting(null)}>
          <p>A film with shows cannot be deleted.</p>
        </ConfirmDialog>
      )}
    </div>
  );
}

function CitiesTab({ cities, onChanged }: { cities: City[]; onChanged: () => void }) {
  const toast = useToast();
  const [error, setError] = useState<unknown>(null);
  const [renaming, setRenaming] = useState<string | null>(null);

  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const d = Object.fromEntries(new FormData(form)) as Record<string, string>;
    setError(null);
    try {
      await api.post('/api/admin/cities', { id: d.id, name: d.name, timeZone: d.zone });
      toast.show(`${d.name} added.`, 'success');
      form.reset();
      onChanged();
    } catch (err) {
      setError(err);
    }
  }

  async function rename(id: string, name: string) {
    try {
      await api.put(`/api/admin/cities/${id}`, { name });
      toast.show('City renamed.', 'success');
      setRenaming(null);
      onChanged();
    } catch (err) {
      toast.error(err);
    }
  }

  return (
    <div className="split">
      <Panel title={`Cities (${cities.length})`}>
        <ul className="plain-list" role="list">
          {cities.map((c) => (
            <li key={c.id}>
              {renaming === c.id ? (
                <form
                  className="inline-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void rename(c.id, String(new FormData(e.currentTarget).get('name')));
                  }}
                >
                  <label className="visually-hidden" htmlFor={`rn-${c.id}`}>
                    New name for {c.name}
                  </label>
                  <input id={`rn-${c.id}`} name="name" defaultValue={c.name} required autoFocus />
                  <button type="submit" className="btn btn--sm">
                    Save
                  </button>
                  <button type="button" className="btn btn--sm btn--ghost" onClick={() => setRenaming(null)}>
                    Cancel
                  </button>
                </form>
              ) : (
                <>
                  <span>
                    <strong>{c.name}</strong> <span className="muted mono">{c.id}</span> <span className="muted">{c.timeZone}</span>
                  </span>
                  <button type="button" className="btn btn--sm btn--ghost" onClick={() => setRenaming(c.id)}>
                    Rename<span className="visually-hidden"> {c.name}</span>
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      </Panel>
      <Panel title="Add a city">
        <form className="form" onSubmit={create}>
          <Field id="c-id" label="Slug" hint="Lowercase letters, digits and hyphens, for example pune.">
            <input id="c-id" name="id" required pattern="[a-z0-9-]{2,40}" />
          </Field>
          <Field id="c-name" label="Name">
            <input id="c-name" name="name" required />
          </Field>
          <Field id="c-zone" label="Time zone">
            <input id="c-zone" name="zone" required defaultValue="Asia/Kolkata" />
          </Field>
          <FormError error={error} />
          <button type="submit" className="btn">
            Add city
          </button>
        </form>
      </Panel>
    </div>
  );
}

function CinemasTab({ cities, onChanged }: { cities: City[]; onChanged: () => void }) {
  const toast = useToast();
  const list = useAsync((signal) => api.get<Page<Cinema>>('/api/cinemas', { size: 100 }, signal), []);
  const [error, setError] = useState<unknown>(null);
  const [deleting, setDeleting] = useState<Cinema | null>(null);
  const cityName = (id: string) => cities.find((c) => c.id === id)?.name ?? id;

  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const d = Object.fromEntries(new FormData(form)) as Record<string, string>;
    setError(null);
    try {
      await api.post('/api/admin/cinemas', { cityId: d.city, name: d.name, address: d.address });
      toast.show(`${d.name} added.`, 'success');
      form.reset();
      list.reload();
      onChanged();
    } catch (err) {
      setError(err);
    }
  }

  async function remove(c: Cinema) {
    setDeleting(null);
    try {
      await api.del(`/api/admin/cinemas/${c.id}`);
      toast.show(`${c.name} deleted.`, 'success');
      list.reload();
      onChanged();
    } catch (err) {
      toast.error(err);
    }
  }

  return (
    <div className="split">
      <Panel title={list.data ? `Cinemas (${list.data.total})` : 'Cinemas'}>
        {list.loading && <Skeleton className="skeleton--card" />}
        {list.error && <ErrorState error={list.error} onRetry={list.reload} />}
        {list.data && (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">City</th>
                  <th scope="col">Address</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.data.items.map((c) => (
                  <tr key={c.id}>
                    <td>{c.name}</td>
                    <td>{cityName(c.cityId)}</td>
                    <td>{c.address}</td>
                    <td>
                      <button type="button" className="btn btn--sm btn--quiet" onClick={() => setDeleting(c)}>
                        Delete<span className="visually-hidden"> {c.name}</span>
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      <Panel title="Add a cinema">
        <form className="form" onSubmit={create}>
          <Field id="ci-city" label="City">
            <select id="ci-city" name="city">
              {cities.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
          <Field id="ci-name" label="Name">
            <input id="ci-name" name="name" required />
          </Field>
          <Field id="ci-addr" label="Address">
            <input id="ci-addr" name="address" required />
          </Field>
          <FormError error={error} />
          <button type="submit" className="btn">
            Add cinema
          </button>
        </form>
      </Panel>
      {deleting && (
        <ConfirmDialog title={`Delete ${deleting.name}?`} confirmLabel="Delete cinema" danger onConfirm={() => remove(deleting)} onClose={() => setDeleting(null)}>
          <p>A cinema with shows cannot be deleted.</p>
        </ConfirmDialog>
      )}
    </div>
  );
}
