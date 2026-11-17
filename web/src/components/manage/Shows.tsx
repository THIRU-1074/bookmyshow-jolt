import { useMemo, useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { CancelShowResult, City, Format, MovieSummary, OwnerCinema, Page, ShowReport } from '../../api/types';
import { useAsync } from '../../hooks/useAsync';
import { addDays, DEFAULT_ZONE, FORMATS, formatDateTime, languageName, money, todayIn, zonedToUtc } from '../../lib/format';
import { ConfirmDialog } from '../ConfirmDialog';
import { EmptyState, ErrorState, LoadingBlock, Skeleton } from '../States';
import { useToast } from '../Toasts';
import { Field, FormError, Panel } from './FormBits';

interface Props {
  prefix: '/api/admin' | '/api/owner';
  cinemas: OwnerCinema[];
  cities: City[];
}

/** Schedule a show on one of the given screens, and list upcoming shows with a cancel action. */
export function Shows({ prefix, cinemas, cities }: Props) {
  const [tick, setTick] = useState(0);
  return (
    <div className="shows-manage">
      <Schedule prefix={prefix} cinemas={cinemas} cities={cities} onCreated={() => setTick((t) => t + 1)} />
      <Upcoming prefix={prefix} refresh={tick} />
    </div>
  );
}

function Schedule({ prefix, cinemas, cities, onCreated }: Props & { onCreated: () => void }) {
  const toast = useToast();
  const movies = useAsync((signal) => api.get<Page<MovieSummary>>('/api/movies', { size: 100, sort: 'title' }, signal), []);
  const screens = useMemo(() => cinemas.flatMap((c) => c.screens.map((s) => ({ ...s, cinema: c }))), [cinemas]);
  const [movieId, setMovieId] = useState('');
  const [screenId, setScreenId] = useState('');
  const [language, setLanguage] = useState('');
  const [format, setFormat] = useState<Format>('2D');
  const [when, setWhen] = useState(`${addDays(todayIn(DEFAULT_ZONE), 1)}T19:00`);
  const [prices, setPrices] = useState<Record<string, string>>({});
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const movie = movies.data?.items.find((m) => String(m.id) === movieId) ?? movies.data?.items[0];
  const screen = screens.find((s) => String(s.id) === screenId) ?? screens[0];
  const lang = movie && movie.languages.includes(language) ? language : (movie?.languages[0] ?? '');
  const zone = cities.find((c) => c.id === screen?.cinema.cityId)?.timeZone ?? DEFAULT_ZONE;

  if (!screens.length) {
    return (
      <Panel title="Schedule a show">
        <p className="muted">Add a screen first; shows run on a screen.</p>
      </Panel>
    );
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!movie || !screen) return;
    const paise: Record<string, number> = {};
    for (const c of screen.layout.categories) {
      const rupees = Number(prices[c.code] ?? '');
      // Rupees in the form, integer paise on the wire.
      paise[c.code] = Math.round(rupees * 100);
    }
    setBusy(true);
    setError(null);
    try {
      await api.post(`${prefix}/shows`, {
        movieId: movie.id,
        screenId: screen.id,
        startsAt: zonedToUtc(when, zone),
        language: lang,
        format,
        prices: paise,
      });
      toast.show(`${movie.title} scheduled at ${screen.cinema.name}, ${screen.name}.`, 'success');
      onCreated();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Schedule a show">
      {movies.loading && <Skeleton className="skeleton--line" />}
      {movies.error && <ErrorState error={movies.error} onRetry={movies.reload} />}
      {movie && screen && (
        <form className="form form--grid" onSubmit={submit}>
          <Field id="sh-movie" label="Film">
            <select id="sh-movie" value={movie.id} onChange={(e) => setMovieId(e.target.value)}>
              {movies.data?.items.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.title}
                </option>
              ))}
            </select>
          </Field>
          <Field id="sh-screen" label="Screen">
            <select id="sh-screen" value={screen.id} onChange={(e) => setScreenId(e.target.value)}>
              {screens.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.cinema.name}, {s.name}
                </option>
              ))}
            </select>
          </Field>
          <Field id="sh-lang" label="Language">
            <select id="sh-lang" value={lang} onChange={(e) => setLanguage(e.target.value)}>
              {movie.languages.map((l) => (
                <option key={l} value={l}>
                  {languageName(l)}
                </option>
              ))}
            </select>
          </Field>
          <Field id="sh-format" label="Format">
            <select id="sh-format" value={format} onChange={(e) => setFormat(e.target.value as Format)}>
              {FORMATS.map((f) => (
                <option key={f}>{f}</option>
              ))}
            </select>
          </Field>
          <Field id="sh-when" label="Starts (cinema local time)">
            <input id="sh-when" type="datetime-local" required value={when} onChange={(e) => setWhen(e.target.value)} />
          </Field>
          <fieldset className="price-set">
            <legend>Ticket price per category, in rupees</legend>
            {screen.layout.categories.map((c) => (
              <label key={c.code} className="price-input">
                <span>{c.name}</span>
                <span className="price-input__box">
                  <span aria-hidden="true">₹</span>
                  <input
                    type="number"
                    min="1"
                    max="10000"
                    step="1"
                    required
                    inputMode="numeric"
                    value={prices[c.code] ?? ''}
                    onChange={(e) => setPrices((p) => ({ ...p, [c.code]: e.target.value }))}
                  />
                </span>
              </label>
            ))}
          </fieldset>
          <FormError error={error} />
          <div className="form-actions">
            <button type="submit" className="btn" disabled={busy}>
              Schedule show
            </button>
          </div>
        </form>
      )}
    </Panel>
  );
}

function Upcoming({ prefix, refresh }: { prefix: string; refresh: number }) {
  const toast = useToast();
  const from = todayIn(DEFAULT_ZONE);
  const { data, error, loading, reload } = useAsync(
    (signal) => api.get<Page<ShowReport>>(`${prefix}/reports/shows`, { from, to: addDays(from, 14), size: 100 }, signal),
    [prefix, refresh],
  );
  const [confirm, setConfirm] = useState<ShowReport | null>(null);

  async function cancel(r: ShowReport) {
    setConfirm(null);
    try {
      const out = await api.post<CancelShowResult>(`${prefix}/shows/${r.showId}/cancel`, {});
      toast.show(
        `Show cancelled. ${out.refunded} refunded, ${out.cancelled} cancelled, ${out.holdsReleased} holds released.`,
        'success',
      );
      reload();
    } catch (e) {
      toast.error(e);
    }
  }

  const upcoming = (data?.items ?? []).filter((r) => new Date(r.startsAt).getTime() > Date.now());
  return (
    <Panel title="Upcoming shows">
      {loading && (
        <LoadingBlock label="Loading shows">
          <Skeleton className="skeleton--line" />
          <Skeleton className="skeleton--line" />
        </LoadingBlock>
      )}
      {error && <ErrorState error={error} onRetry={reload} />}
      {data && !loading && !upcoming.length && <EmptyState title="Nothing scheduled in the next two weeks" art="reel" />}
      {!!upcoming.length && !loading && (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Film</th>
                <th scope="col">Cinema</th>
                <th scope="col">Starts</th>
                <th scope="col" className="num">
                  Sold
                </th>
                <th scope="col" className="num">
                  Revenue
                </th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {upcoming.slice(0, 60).map((r) => (
                <tr key={r.showId}>
                  <td>{r.movieTitle}</td>
                  <td>{r.cinemaName}</td>
                  <td>{formatDateTime(r.startsAt)}</td>
                  <td className="num">
                    {r.booked}/{r.capacity}
                  </td>
                  <td className="num">{money(r.revenue)}</td>
                  <td>
                    {r.status === 'CANCELLED' ? (
                      <span className="muted">✕ Cancelled</span>
                    ) : (
                      <button type="button" className="btn btn--sm btn--quiet" onClick={() => setConfirm(r)}>
                        Cancel<span className="visually-hidden"> {r.movieTitle} at {formatDateTime(r.startsAt)}</span>
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {confirm && (
        <ConfirmDialog
          title={`Cancel ${confirm.movieTitle}?`}
          confirmLabel="Cancel show and refund"
          cancelLabel="Keep show"
          danger
          onConfirm={() => cancel(confirm)}
          onClose={() => setConfirm(null)}
        >
          <p>
            {formatDateTime(confirm.startsAt)} at {confirm.cinemaName}. Every confirmed booking ({confirm.booked} seats, {money(confirm.revenue)}) is
            refunded in full and held seats are released. This cannot be undone.
          </p>
        </ConfirmDialog>
      )}
    </Panel>
  );
}
