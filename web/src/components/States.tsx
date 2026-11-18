import type { ReactNode } from 'react';
import { ApiError } from '../api/client';

export function Skeleton({ className = '', label }: { className?: string; label?: string }) {
  return <div className={`skeleton ${className}`} aria-hidden={label ? undefined : true} aria-label={label} />;
}

export function LoadingBlock({ label = 'Loading', children }: { label?: string; children: ReactNode }) {
  return (
    <div role="status" aria-busy="true">
      <span className="visually-hidden">{label}</span>
      {children}
    </div>
  );
}

export function ErrorState({ error, onRetry, title = 'That did not load' }: { error: unknown; onRetry?: () => void; title?: string }) {
  const msg = error instanceof Error ? error.message : 'Something went wrong.';
  const ref = error instanceof ApiError ? error.requestId : undefined;
  return (
    <div className="state state--error" role="alert">
      <svg className="state__art" viewBox="0 0 120 80" aria-hidden="true">
        <rect x="10" y="14" width="100" height="52" rx="6" className="art-line" />
        <path d="M30 30 L50 50 M50 30 L30 50 M70 30 L90 50 M90 30 L70 50" className="art-line" />
      </svg>
      <h2>{title}</h2>
      <p>{msg}</p>
      {ref && <p className="state__ref">Reference {ref}</p>}
      {onRetry && (
        <button type="button" className="btn btn--ghost" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function EmptyState({ title, children, art = 'seats' }: { title: string; children?: ReactNode; art?: 'seats' | 'reel' | 'ticket' }) {
  return (
    <div className="state">
      <svg className="state__art" viewBox="0 0 120 80" aria-hidden="true">
        {art === 'seats' && (
          <g className="art-line">
            {[0, 1, 2, 3, 4].map((i) => (
              <path key={i} d={`M${14 + i * 20} 58 v-14 a6 6 0 0 1 6 -6 h4 a6 6 0 0 1 6 6 v14`} />
            ))}
            <path d="M8 64 H112" />
            <path d="M24 20 Q60 6 96 20" />
          </g>
        )}
        {art === 'reel' && (
          <g className="art-line">
            <circle cx="60" cy="40" r="28" />
            <circle cx="60" cy="40" r="5" />
            {[0, 1, 2, 3, 4].map((i) => {
              const a = (i * 72 * Math.PI) / 180;
              return <circle key={i} cx={60 + Math.cos(a) * 16} cy={40 + Math.sin(a) * 16} r="6" />;
            })}
          </g>
        )}
        {art === 'ticket' && (
          <g className="art-line">
            <path d="M14 20 h92 v12 a8 8 0 0 0 0 16 v12 h-92 v-12 a8 8 0 0 0 0 -16 z" />
            <path d="M44 22 v36" strokeDasharray="3 4" />
          </g>
        )}
      </svg>
      <h2>{title}</h2>
      {children}
    </div>
  );
}
