import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Booking } from '../api/types';
import { PageTitle, StatusBadge } from '../components/bits';
import { EmptyState, ErrorState, LoadingBlock, Skeleton } from '../components/States';
import { Ticket } from '../components/Ticket';
import { useAsync } from '../hooks/useAsync';
import { money } from '../lib/format';

export function BookingDetail() {
  const { id = '' } = useParams();
  const { data: b, error, reload } = useAsync((signal) => api.get<Booking>(`/api/bookings/${encodeURIComponent(id)}`, undefined, signal), [id]);

  // The gateway's webhook can land a moment after its redirect; poll briefly while pending.
  useEffect(() => {
    if (b?.status !== 'PAYMENT_PENDING') return;
    const t = window.setTimeout(reload, 1500);
    return () => window.clearTimeout(t);
  }, [b, reload]);

  if (error) {
    return (
      <div className="wrap section narrow">
        <PageTitle>Booking</PageTitle>
        <ErrorState error={error} onRetry={reload} />
      </div>
    );
  }
  if (!b) {
    return (
      <div className="wrap section narrow">
        <LoadingBlock label="Loading booking">
          <Skeleton className="skeleton--title" />
          <Skeleton className="skeleton--ticket" />
        </LoadingBlock>
      </div>
    );
  }

  if (b.status === 'CONFIRMED') {
    return (
      <div className="wrap section narrow confirmed">
        <PageTitle>You are in. Enjoy the film.</PageTitle>
        <p className="lede">
          Booking <strong className="mono">{b.code}</strong> is confirmed. Keep this ticket on your phone or print it.
        </p>
        <Ticket booking={b} reveal />
        <div className="confirmed__actions no-print">
          <button type="button" className="btn" onClick={() => window.print()}>
            Print ticket
          </button>
          <Link className="btn btn--ghost" to="/bookings">
            My bookings
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="wrap section narrow">
      <PageTitle>Booking {b.code}</PageTitle>
      <p>
        <StatusBadge status={b.status} />
      </p>
      {b.status === 'PAYMENT_PENDING' && (
        <EmptyState title="Waiting for the payment result" art="ticket">
          <p>This usually takes a second or two. The page refreshes on its own.</p>
          <Link className="btn btn--ghost" to={`/checkout/${b.id}`}>
            Back to checkout
          </Link>
        </EmptyState>
      )}
      {b.status === 'HELD' && (
        <EmptyState title="Payment not finished" art="ticket">
          <p>Your seats are still held. Finish paying before the hold runs out.</p>
          <Link className="btn" to={`/checkout/${b.id}`}>
            Go to checkout
          </Link>
        </EmptyState>
      )}
      {(b.status === 'REFUNDED' || b.status === 'CANCELLED' || b.status === 'EXPIRED') && (
        <EmptyState title={b.status === 'EXPIRED' ? 'This hold ran out' : 'This booking was cancelled'} art="ticket">
          <p>
            {b.status === 'REFUNDED'
              ? `${money(b.refundAmount)} was refunded to you.`
              : b.status === 'EXPIRED'
                ? 'The seats went back on sale and you were not charged.'
                : 'No refund applied.'}
          </p>
          <Link className="btn btn--ghost" to={`/shows/${b.show.id}`}>
            Choose seats again
          </Link>
        </EmptyState>
      )}
    </div>
  );
}
