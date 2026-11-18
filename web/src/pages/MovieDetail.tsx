import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { MovieDetail as Movie, ShowSlot, ShowTimes } from '../api/types';
import { Badge, PageTitle } from '../components/bits';
import { DateStrip } from '../components/DateStrip';
import { Poster } from '../components/Poster';
import { EmptyState, ErrorState, LoadingBlock, Skeleton } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { useCity } from '../hooks/useCity';
import { CERT_LABEL, FORMATS, languageName, money, runtime, todayIn } from '../lib/format';

const AVAIL: Record<ShowSlot['availability'], string> = {
  AVAILABLE: 'Seats available',
  FILLING_FAST: 'Filling fast',
  SOLD_OUT: 'Sold out',
};

export function MovieDetail() {
  const { id } = useParams();
  const movieId = Number(id);
  const { city, zone, openPicker } = useCity();
  const today = todayIn(zone);
  const [date, setDate] = useState(today);
  const [lang, setLang] = useState<string | null>(null);
  const [format, setFormat] = useState<string | null>(null);

  const movie = useAsync((signal) => api.get<Movie>(`/api/movies/${movieId}`, undefined, signal), [movieId]);
  const shows = useAsync(
    (signal) =>
      city
        ? api.get<ShowTimes>(`/api/movies/${movieId}/shows`, { city: city.id, date, language: lang, format }, signal)
        : Promise.resolve(null),
    [movieId, city?.id, date, lang, format],
  );

  if (movie.error) {
    return (
      <div className="wrap section">
        <PageTitle>Film</PageTitle>
        <ErrorState error={movie.error} onRetry={movie.reload} />
      </div>
    );
  }
  const m = movie.data;

  return (
    <>
      <section className="film-hero">
        {m && (
          <div className="film-hero__backdrop" aria-hidden="true">
            <Poster title={m.title} posterUrl={m.posterUrl} className="hero__blur" />
          </div>
        )}
        <div className="wrap film-hero__inner">
          {m ? (
            <Poster title={m.title} posterUrl={m.posterUrl} className="film-hero__poster" eager />
          ) : (
            <Skeleton className="skeleton--poster film-hero__poster" />
          )}
          <div className="film-hero__copy">
            {m ? (
              <>
                <PageTitle className="film-hero__title">{m.title}</PageTitle>
                <p className="hero__meta">
                  <Badge tone="cert">
                    <abbr title={CERT_LABEL[m.certificate]}>{m.certificate}</abbr>
                  </Badge>
                  <span>{runtime(m.durationMinutes)}</span>
                  <span>{m.genres.join(', ')}</span>
                  <span>
                    Released{' '}
                    {new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
                      new Date(m.releaseDate),
                    )}
                  </span>
                </p>
                {m.description && <p className="film-hero__desc">{m.description}</p>}
                <p className="hero__langs">Playing in {m.languages.map(languageName).join(', ')}</p>
              </>
            ) : (
              <LoadingBlock label="Loading film">
                <Skeleton className="skeleton--title" />
                <Skeleton className="skeleton--line" />
                <Skeleton className="skeleton--line" />
                <Skeleton className="skeleton--line short" />
              </LoadingBlock>
            )}
          </div>
        </div>
      </section>

      <section className="wrap section" aria-labelledby="showtimes">
        <h2 id="showtimes" className="section__title">
          Showtimes{city ? ` in ${city.name}` : ''}
        </h2>
        {!city ? (
          <EmptyState title="Which city are you in?" art="seats">
            <p>Showtimes depend on where you are watching.</p>
            <button type="button" className="btn" onClick={openPicker}>
              Choose city
            </button>
          </EmptyState>
        ) : (
          <>
            <DateStrip today={today} value={date} onChange={setDate} />
            {m && (
              <div className="showtime-filters">
                <fieldset className="chips chips--inline">
                  <legend>Language</legend>
                  {m.languages.map((l) => (
                    <button key={l} type="button" className="chip" aria-pressed={lang === l} onClick={() => setLang(lang === l ? null : l)}>
                      {languageName(l)}
                    </button>
                  ))}
                </fieldset>
                <fieldset className="chips chips--inline">
                  <legend>Format</legend>
                  {FORMATS.map((f) => (
                    <button key={f} type="button" className="chip" aria-pressed={format === f} onClick={() => setFormat(format === f ? null : f)}>
                      {f}
                    </button>
                  ))}
                </fieldset>
              </div>
            )}
            {shows.loading && (
              <LoadingBlock label="Loading showtimes">
                {[0, 1, 2].map((i) => (
                  <Skeleton key={i} className="skeleton--cinema" />
                ))}
              </LoadingBlock>
            )}
            {shows.error && <ErrorState error={shows.error} onRetry={shows.reload} />}
            {!shows.loading && shows.data && !shows.data.cinemas.length && (
              <EmptyState title="No shows on this day" art="seats">
                <p>Every seat is empty because nothing is scheduled. Try another date{lang || format ? ' or clear the filters' : ''}.</p>
              </EmptyState>
            )}
            {!shows.loading && shows.data && !!shows.data.cinemas.length && (
              <ul className="cinemas" role="list">
                {shows.data.cinemas.map((c) => (
                  <li key={c.id} className="cinema">
                    <div className="cinema__head">
                      <h3>{c.name}</h3>
                      <p>{c.address}</p>
                    </div>
                    <ul className="times" role="list" aria-label={`Showtimes at ${c.name}`}>
                      {c.shows.map((s) => (
                        <li key={s.id}>
                          <TimeChip show={s} />
                        </li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </section>
    </>
  );
}

function TimeChip({ show: s }: { show: ShowSlot }) {
  const label = `${s.localTime}, ${languageName(s.language)}, ${s.format}, from ${money(s.minPrice)}, ${AVAIL[s.availability]}`;
  const inner = (
    <>
      <span className="time__clock">{s.localTime}</span>
      <span className="time__tags" aria-hidden="true">
        <span>{s.format}</span>
        <span>{languageName(s.language)}</span>
      </span>
      <span className="time__price" aria-hidden="true">
        from {money(s.minPrice)}
      </span>
      {s.availability !== 'AVAILABLE' && (
        <span className="time__avail" aria-hidden="true">
          {s.availability === 'SOLD_OUT' ? '✕ Sold out' : '◔ Filling fast'}
        </span>
      )}
    </>
  );
  if (s.availability === 'SOLD_OUT') {
    return (
      <span className="time time--soldout" aria-label={label} role="note">
        {inner}
      </span>
    );
  }
  return (
    <Link to={`/shows/${s.id}`} className={`time time--${s.availability.toLowerCase()}`} aria-label={label}>
      {inner}
    </Link>
  );
}
