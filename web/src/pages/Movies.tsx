import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { MovieSummary, Page } from '../api/types';
import { PageTitle } from '../components/bits';
import { DateStrip } from '../components/DateStrip';
import { MovieCard, MovieGridSkeleton } from '../components/MovieCard';
import { EmptyState, ErrorState } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { useCity } from '../hooks/useCity';
import { CERTIFICATES, FORMATS, GENRES, LANGUAGES, todayIn } from '../lib/format';

const list = (v: string | null) => (v ? v.split(',').filter(Boolean) : []);

export function Movies() {
  const { city, zone, openPicker } = useCity();
  const [params, setParams] = useSearchParams();
  const today = todayIn(zone);
  const date = params.get('date') ?? today;
  const page = Number(params.get('page') ?? '1') || 1;
  const filters = {
    genre: list(params.get('genre')),
    language: list(params.get('language')),
    certificate: list(params.get('certificate')),
    format: list(params.get('format')),
  };
  const [q, setQ] = useState(params.get('q') ?? '');

  // Debounce typing into the URL so every keystroke does not hit the API.
  useEffect(() => {
    const t = window.setTimeout(() => {
      if ((params.get('q') ?? '') === q) return;
      update({ q: q.trim().length >= 2 ? q : null, page: null });
    }, 300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q]);

  function update(next: Record<string, string | null>) {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v) p.set(k, v);
      else p.delete(k);
    }
    setParams(p, { replace: true });
  }

  function toggle(key: keyof typeof filters, value: string) {
    const cur = new Set(filters[key]);
    if (cur.has(value)) cur.delete(value);
    else cur.add(value);
    update({ [key]: [...cur].join(',') || null, page: null });
  }

  const key = params.toString();
  const { data, error, loading, reload } = useAsync(
    (signal) =>
      api.get<Page<MovieSummary>>(
        '/api/movies',
        { city: city?.id, date: city ? date : undefined, q: params.get('q'), ...filters, page, size: 12, sort: 'release' },
        signal,
      ),
    [key, city?.id],
  );

  const group = (name: string, key: keyof typeof filters, values: readonly string[], label: (v: string) => string = (v) => v) => (
    <fieldset className="chips">
      <legend>{name}</legend>
      {values.map((v) => {
        const on = filters[key].includes(v);
        return (
          <button key={v} type="button" className="chip" aria-pressed={on} onClick={() => toggle(key, v)}>
            {on && <span aria-hidden="true">✓ </span>}
            {label(v)}
          </button>
        );
      })}
    </fieldset>
  );

  return (
    <div className="wrap section">
      <PageTitle>Films{city ? ` in ${city.name}` : ''}</PageTitle>
      {!city && (
        <p className="note">
          Showing every film in the catalogue.{' '}
          <button type="button" className="link-btn" onClick={openPicker}>
            Choose a city
          </button>{' '}
          to see only what is playing near you.
        </p>
      )}
      <div className="filters">
        <label className="search">
          <span className="visually-hidden">Search films by title</span>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="6.5" />
            <path d="M16 16l5 5" />
          </svg>
          <input type="search" placeholder="Search by title" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        {city && <DateStrip today={today} value={date} onChange={(d) => update({ date: d === today ? null : d, page: null })} />}
        <details className="filter-drawer">
          <summary>
            Filters
            {Object.values(filters).flat().length > 0 && <span className="count">{Object.values(filters).flat().length}</span>}
          </summary>
          <div className="filter-drawer__body">
            {group('Genre', 'genre', GENRES)}
            {group('Language', 'language', Object.keys(LANGUAGES), (c) => LANGUAGES[c] ?? c)}
            {group('Certificate', 'certificate', CERTIFICATES)}
            {group('Format', 'format', FORMATS)}
          </div>
        </details>
      </div>
      {loading && <MovieGridSkeleton count={8} />}
      {error && <ErrorState error={error} onRetry={reload} />}
      {data && !loading && !data.items.length && (
        <EmptyState title="Nothing matches that" art="reel">
          <p>Try fewer filters, a different date or another spelling.</p>
          <button type="button" className="btn btn--ghost" onClick={() => setParams(new URLSearchParams(), { replace: true })}>
            Clear search and filters
          </button>
        </EmptyState>
      )}
      {data && !loading && !!data.items.length && (
        <>
          <p className="result-count" role="status">
            {data.total} {data.total === 1 ? 'film' : 'films'}
          </p>
          <ul className="movie-grid" role="list">
            {data.items.map((m) => (
              <li key={m.id}>
                <MovieCard movie={m} />
              </li>
            ))}
          </ul>
          {data.totalPages > 1 && (
            <nav className="pager" aria-label="Pages">
              <button type="button" className="btn btn--ghost" disabled={page <= 1} onClick={() => update({ page: String(page - 1) })}>
                Previous
              </button>
              <span>
                Page {data.page} of {data.totalPages}
              </span>
              <button
                type="button"
                className="btn btn--ghost"
                disabled={page >= data.totalPages}
                onClick={() => update({ page: String(page + 1) })}
              >
                Next
              </button>
            </nav>
          )}
        </>
      )}
    </div>
  );
}
