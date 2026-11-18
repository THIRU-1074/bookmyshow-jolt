import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { MovieSummary, Page } from '../api/types';
import { Badge, PageTitle } from '../components/bits';
import { MovieCard, MovieGridSkeleton } from '../components/MovieCard';
import { Poster } from '../components/Poster';
import { EmptyState, ErrorState, LoadingBlock, Skeleton } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { useCity } from '../hooks/useCity';
import { CERT_LABEL, languageName, runtime } from '../lib/format';

export function Home() {
  const { city, cities, setCity } = useCity();

  if (!city) {
    return (
      <section className="wrap welcome">
        <PageTitle className="welcome__title">Lights down in your city.</PageTitle>
        <p className="lede">Pick a city to see what is playing tonight and book your seats in a minute.</p>
        {cities ? (
          <div className="city-grid city-grid--large">
            {cities.map((c) => (
              <button key={c.id} type="button" className="city-tile" onClick={() => setCity(c.id)}>
                <span className="city-tile__initial" aria-hidden="true">
                  {c.name.slice(0, 1)}
                </span>
                {c.name}
              </button>
            ))}
          </div>
        ) : (
          <LoadingBlock label="Loading cities">
            <div className="city-grid city-grid--large">
              {[0, 1, 2].map((i) => (
                <Skeleton key={i} className="skeleton--tile" />
              ))}
            </div>
          </LoadingBlock>
        )}
      </section>
    );
  }
  return <NowShowing cityId={city.id} cityName={city.name} />;
}

function NowShowing({ cityId, cityName }: { cityId: string; cityName: string }) {
  const { data, error, loading, reload } = useAsync(
    (signal) => api.get<Page<MovieSummary>>('/api/movies', { city: cityId, size: 24 }, signal),
    [cityId],
  );
  const movies = data?.items ?? [];

  return (
    <>
      {loading ? (
        <div className="hero hero--loading" aria-hidden="true">
          <div className="wrap hero__inner">
            <div className="hero__stage">
              <Skeleton className="skeleton--poster hero__poster" />
            </div>
            <div className="hero__copy">
              <Skeleton className="skeleton--title" />
              <Skeleton className="skeleton--line" />
              <Skeleton className="skeleton--line short" />
            </div>
          </div>
        </div>
      ) : movies.length ? (
        <Marquee movies={movies.slice(0, 5)} cityName={cityName} />
      ) : null}

      <section className="wrap section" aria-labelledby="now-showing">
        <div className="section__head">
          {movies.length || loading ? (
            <PageTitle className="section__title">
              <span id="now-showing">Now showing in {cityName}</span>
            </PageTitle>
          ) : (
            <PageTitle className="section__title">
              <span id="now-showing">{cityName}</span>
            </PageTitle>
          )}
          <Link to="/movies" className="link-more">
            Search and filter all films
          </Link>
        </div>
        {loading && <MovieGridSkeleton />}
        {error && <ErrorState error={error} onRetry={reload} title="The film list did not load" />}
        {!loading && !error && !movies.length && (
          <EmptyState title="The projector is resting" art="reel">
            <p>No shows are scheduled in {cityName} today. Try another city or check back tomorrow.</p>
          </EmptyState>
        )}
        {!!movies.length && (
          <ul className="movie-grid" role="list">
            {movies.map((m) => (
              <li key={m.id}>
                <MovieCard movie={m} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

const reduceMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

function Marquee({ movies, cityName }: { movies: MovieSummary[]; cityName: string }) {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const count = movies.length;
  const go = (i: number) => setIndex((i + count) % count);

  useEffect(() => {
    // Auto-advance only when the visitor is not interacting and has not asked for less motion.
    if (paused || count < 2 || reduceMotion()) return;
    const t = window.setTimeout(() => setIndex((i) => (i + 1) % count), 7000);
    return () => window.clearTimeout(t);
  }, [index, paused, count]);

  const m = movies[index] as MovieSummary;
  return (
    <section
      className="hero"
      aria-roledescription="carousel"
      aria-label={`Featured films in ${cityName}`}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <div className="hero__backdrop" aria-hidden="true">
        <Poster key={`bg-${m.id}`} title={m.title} posterUrl={m.posterUrl} className="hero__blur" />
      </div>
      <div className="wrap hero__inner">
        <div className="hero__stage">
          {movies.map((mv, i) => (
            <div
              key={mv.id}
              className={`hero__slide${i === index ? ' is-active' : ''}`}
              aria-hidden={i !== index}
              aria-roledescription="slide"
              aria-label={`${i + 1} of ${count}`}
            >
              <Poster title={mv.title} posterUrl={mv.posterUrl} className="hero__poster" eager={i === 0} />
            </div>
          ))}
        </div>
        <div className="hero__copy" aria-live={paused ? 'polite' : 'off'}>
          <p className="hero__kicker">Tonight in {cityName}</p>
          <h2 className="hero__title" key={m.id}>
            {m.title}
          </h2>
          <p className="hero__meta">
            <Badge tone="cert">
              <abbr title={CERT_LABEL[m.certificate]}>{m.certificate}</abbr>
            </Badge>
            <span>{runtime(m.durationMinutes)}</span>
            <span>{m.genres.join(', ')}</span>
          </p>
          <p className="hero__langs">{m.languages.map(languageName).join(', ')}</p>
          <div className="hero__actions">
            <Link className="btn btn--lg" to={`/movies/${m.id}`}>
              Book seats<span className="visually-hidden"> for {m.title}</span>
            </Link>
          </div>
          {count > 1 && (
            <div className="hero__controls">
              <button type="button" className="icon-btn" onClick={() => go(index - 1)} aria-label="Previous film">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M15 5l-7 7 7 7" />
                </svg>
              </button>
              <div className="hero__dots">
                {movies.map((mv, i) => (
                  <button
                    key={mv.id}
                    type="button"
                    className="hero__dot"
                    aria-label={`Show ${mv.title}`}
                    aria-current={i === index ? 'true' : undefined}
                    onClick={() => go(i)}
                  />
                ))}
              </div>
              <button type="button" className="icon-btn" onClick={() => go(index + 1)} aria-label="Next film">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M9 5l7 7-7 7" />
                </svg>
              </button>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
