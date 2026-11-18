import { useEffect, useRef, type ReactNode } from 'react';
import type { BookingStatus } from '../api/types';
import { codeMatrix } from '../lib/art';
import { STATUS_LABELS } from '../lib/format';

/** The page's h1. It takes focus on mount so route changes are announced (SPEC section 8). */
export function PageTitle({ children, className = '' }: { children: ReactNode; className?: string }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    // Skip the very first page load: focus belongs to the document there.
    if (document.body.dataset.navigated) ref.current?.focus({ preventScroll: true });
    document.body.dataset.navigated = '1';
  }, []);
  return (
    <h1 ref={ref} tabIndex={-1} className={`page-title ${className}`}>
      {children}
    </h1>
  );
}

const STATUS_GLYPH: Record<BookingStatus, string> = {
  HELD: '◷',
  PAYMENT_PENDING: '↻',
  CONFIRMED: '✓',
  EXPIRED: '⌛',
  CANCELLED: '✕',
  REFUNDED: '↩',
};

export function StatusBadge({ status }: { status: BookingStatus }) {
  return (
    <span className={`badge badge--${status.toLowerCase()}`}>
      <span aria-hidden="true">{STATUS_GLYPH[status]}</span> {STATUS_LABELS[status] ?? status}
    </span>
  );
}

export function Wordmark() {
  return (
    <span className="wordmark">
      <svg viewBox="0 0 28 28" className="wordmark__mark" aria-hidden="true">
        <path d="M3 22 Q14 4 25 22" className="wordmark__arc" />
        <circle cx="7" cy="12" r="2" className="wordmark__bulb" />
        <circle cx="14" cy="8.5" r="2" className="wordmark__bulb" />
        <circle cx="21" cy="12" r="2" className="wordmark__bulb" />
        <rect x="5" y="22" width="18" height="3" rx="1.5" className="wordmark__stage" />
      </svg>
      <span className="wordmark__text">Houselights</span>
    </span>
  );
}

export function CodeMatrix({ code, size = 25 }: { code: string; size?: number }) {
  const m = codeMatrix(code, size);
  let d = '';
  m.forEach((row, r) =>
    row.forEach((on, c) => {
      if (on) d += `M${c} ${r}h1v1h-1z`;
    }),
  );
  return (
    <svg className="code-matrix" viewBox={`-2 -2 ${size + 4} ${size + 4}`} role="img" aria-label={`Entry code pattern for booking ${code}`}>
      <rect x="-2" y="-2" width={size + 4} height={size + 4} className="code-matrix__bg" />
      <path d={d} className="code-matrix__fg" shapeRendering="crispEdges" />
    </svg>
  );
}

export function Badge({ children, tone = 'plain' }: { children: ReactNode; tone?: 'plain' | 'format' | 'lang' | 'cert' }) {
  return <span className={`tag tag--${tone}`}>{children}</span>;
}
