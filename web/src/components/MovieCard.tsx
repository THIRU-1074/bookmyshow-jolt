import { Link } from 'react-router-dom';
import type { MovieSummary } from '../api/types';
import { languageName, runtime } from '../lib/format';
import { Poster } from './Poster';
import { LoadingBlock, Skeleton } from './States';

export function MovieCard({ movie }: { movie: MovieSummary }) {
  return (
    <Link to={`/movies/${movie.id}`} className="movie-card">
      <Poster title={movie.title} posterUrl={movie.posterUrl} />
      <span className="movie-card__cert" aria-label={`Certificate ${movie.certificate}`}>
        {movie.certificate}
      </span>
      <span className="movie-card__body">
        <span className="movie-card__title">{movie.title}</span>
        <span className="movie-card__meta">
          {runtime(movie.durationMinutes)}, {movie.genres.slice(0, 2).join(' and ')}
        </span>
        <span className="movie-card__meta">{movie.languages.map(languageName).join(', ')}</span>
      </span>
    </Link>
  );
}

export function MovieGridSkeleton({ count = 8 }: { count?: number }) {
  return (
    <LoadingBlock label="Loading films">
      <div className="movie-grid">
        {Array.from({ length: count }, (_, i) => (
          <div key={i}>
            <Skeleton className="skeleton--poster" />
            <Skeleton className="skeleton--line" />
            <Skeleton className="skeleton--line short" />
          </div>
        ))}
      </div>
    </LoadingBlock>
  );
}
