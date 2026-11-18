import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { api, ApiError, newIdempotencyKey } from '../api/client';
import type { Booking, Checkout as CheckoutDto } from '../api/types';
import { PageTitle, StatusBadge } from '../components/bits';
import { EmptyState, ErrorState, LoadingBlock, Skeleton } from '../components/States';
import { useToast } from '../components/Toasts';
import { useAsync } from '../hooks/useAsync';
import { useCountdown } from '../hooks/useCountdown';
import { formatLongDate, mmss, money, sortSeatIds } from '../lib/format';

const MARKS: [number, string][] = [
  [120, 'Two minutes left to pay.'],
  [60, 'One minute left to pay.'],
  [30, 'Thirty seconds left to pay.'],
];

export function Checkout() {
  const { id = '' } = useParams();
  const { data: b, error, reload } = useAsync((signal) => api.get<Booking>(`/api/bookings/${encodeURIComponent(id)}`, undefined, signal), [id]);

  if (error) {
    return (
      <div className="wrap section narrow">
        <PageTitle>Checkout</PageTitle>
        <ErrorState error={error} onRetry={reload} />
      </div>
    );
  }
  if (!b) {
    return (
      <div className="wrap section narrow">
        <LoadingBlock label="Loading booking">
          <Skeleton className="skeleton--title" />
          <Skeleton className="skeleton--card" />
        </LoadingBlock>
      </div>
    );
  }
  if (b.status === 'CONFIRMED' || b.status === 'REFUNDED' || b.status === 'CANCELLED') {
    return <Navigate to={`/bookings/${b.id}`} replace />;
  }
  return <Pay booking={b} />;
}

function Pay({ booking: b }: { booking: Booking }) {
  const toast = useToast();
  const left = useCountdown(b.expiresAt);
  const [announce, setAnnounce] = useState('');
  const [paying, setPaying] = useState(false);
  const [released, setReleased] = useState(false);
  const payKey = useRef<string | null>(null);
  const lastMark = useRef(Infinity);
  const expired = b.status === 'EXPIRED' || left === 0;

  // Announce sparingly so screen readers are not flooded every second. Thresholds rather than
  // equality, because a throttled background tab can skip seconds.
  useEffect(() => {
    if (left === null) return;
    const mark = [...MARKS].reverse().find(([at]) => left <= at && left > 0 && at < lastMark.current);
    if (mark) {
      setAnnounce(mark[1]);
      lastMark.current = mark[0];
    }
    if (left === 0) setAnnounce('Your hold has expired.');
  }, [left]);

  async function pay() {
    setPaying(true);
    payKey.current ??= newIdempotencyKey();
    try {
      const out = await api.post<CheckoutDto>(`/api/bookings/${b.id}/checkout`, undefined, payKey.current);
      // The gateway is a server-rendered page, so leave the SPA for it.
      window.location.assign(out.checkoutUrl);
    } catch (e) {
      payKey.current = null;
      setPaying(false);
      toast.error(e instanceof ApiError && e.status === 410 ? new Error('Your hold has expired.') : e);
    }
  }

  async function release() {
    try {
      await api.post(`/api/bookings/${b.id}/cancel`);
      setReleased(true);
      toast.show('Seats released.', 'info');
    } catch (e) {
      toast.error(e);
    }
  }

  if (released) {
    return (
      <div className="wrap section narrow">
        <PageTitle>Seats released</PageTitle>
        <EmptyState title="Your seats are back on sale" art="seats">
          <Link className="btn" to={`/shows/${b.show.id}`}>
            Pick seats again
          </Link>
        </EmptyState>
      </div>
    );
  }

  const seats = sortSeatIds(b.seats.map((s) => s.id));
  return (
    <div className="wrap section narrow checkout">
      <PageTitle>Checkout</PageTitle>
      <div className="checkout__card">
        <div className={`countdown${left !== null && left < 60 ? ' is-urgent' : ''}${expired ? ' is-over' : ''}`}>
          <p className="countdown__label">{expired ? 'Hold expired' : 'Time left to pay'}</p>
          <p className="countdown__clock" aria-hidden="true">
            {mmss(left ?? 0)}
          </p>
          <svg className="countdown__ring" viewBox="0 0 100 100" aria-hidden="true">
            <circle cx="50" cy="50" r="46" pathLength="100" className="countdown__track" />
          </svg>
        </div>
        <p className="visually-hidden" aria-live="polite">
          {announce}
        </p>
        <dl className="kv">
          <dt>Film</dt>
          <dd>{b.show.movieTitle}</dd>
          <dt>Cinema</dt>
          <dd>
            {b.show.cinemaName}, {b.show.screenName}
          </dd>
          <dt>When</dt>
          <dd>
            {formatLongDate(b.show.startsAt)} at {b.show.localTime}
          </dd>
          <dt>Seats</dt>
          <dd>
            <ul className="seat-tokens" role="list">
              {seats.map((s) => {
                const seat = b.seats.find((x) => x.id === s);
                return (
                  <li key={s}>
                    {s}
                    <span className="seat-tokens__price">{money(seat?.price ?? 0)}</span>
                  </li>
                );
              })}
            </ul>
          </dd>
          <dt>Status</dt>
          <dd>
            <StatusBadge status={b.status} />
          </dd>
          <dt className="kv__total">Total</dt>
          <dd className="kv__total">{money(b.amount)}</dd>
        </dl>
        {expired ? (
          <div className="checkout__actions">
            <p className="note">The seats went back on sale. You have not been charged.</p>
            <Link className="btn" to={`/shows/${b.show.id}`}>
              Pick seats again
            </Link>
          </div>
        ) : (
          <div className="checkout__actions">
            <button type="button" className="btn btn--lg" onClick={pay} disabled={paying}>
              {paying ? 'Opening payment' : `Pay ${money(b.amount)}`}
            </button>
            {b.status === 'HELD' && (
              <button type="button" className="btn btn--quiet" onClick={release}>
                Release seats
              </button>
            )}
            <p className="note">
              {b.status === 'PAYMENT_PENDING'
                ? 'A payment was started. Pay again to reopen the gateway.'
                : 'You will be taken to our test payment page. No real money moves.'}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
