import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Booking, BookingStatus, Page } from '../api/types';
import { PageTitle, StatusBadge } from '../components/bits';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { GeneratedPoster } from '../components/Poster';
import { EmptyState, ErrorState, LoadingBlock, Skeleton } from '../components/States';
import { useToast } from '../components/Toasts';
import { useAsync } from '../hooks/useAsync';
import { formatLongDate, money, refundPreview, sortSeatIds, STATUS_LABELS } from '../lib/format';

export function MyBookings() {
  const toast = useToast();
  const [status, setStatus] = useState<BookingStatus[]>([]);
  const [page, setPage] = useState(1);
  const [confirming, setConfirming] = useState<Booking | null>(null);
  const { data, error, loading, reload } = useAsync(
    (signal) => api.get<Page<Booking>>('/api/bookings', { status, page, size: 10 }, signal),
    [status.join(','), page],
  );

  function toggle(s: BookingStatus) {
    setStatus((cur) => (cur.includes(s) ? cur.filter((x) => x !== s) : [...cur, s]));
    setPage(1);
  }

  async function cancel(b: Booking) {
    setConfirming(null);
    try {
      const out = await api.post<Booking>(`/api/bookings/${b.id}/cancel`);
      toast.show(out.refundAmount ? `Booking cancelled. ${money(out.refundAmount)} refunded.` : 'Booking cancelled.', 'success');
      reload();
    } catch (e) {
      toast.error(e);
      reload();
    }
  }

  const preview = confirming ? refundPreview(confirming) : null;

  return (
    <div className="wrap section">
      <PageTitle>My bookings</PageTitle>
      <fieldset className="chips">
        <legend>Status</legend>
        {(Object.keys(STATUS_LABELS) as BookingStatus[]).map((s) => (
          <button key={s} type="button" className="chip" aria-pressed={status.includes(s)} onClick={() => toggle(s)}>
            {status.includes(s) && <span aria-hidden="true">✓ </span>}
            {STATUS_LABELS[s]}
          </button>
        ))}
      </fieldset>
      {loading && (
        <LoadingBlock label="Loading bookings">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="skeleton--booking" />
          ))}
        </LoadingBlock>
      )}
      {error && <ErrorState error={error} onRetry={reload} />}
      {data && !loading && !data.items.length && (
        <EmptyState title={status.length ? 'No bookings with that status' : 'No tickets yet'} art="ticket">
          <p>{status.length ? 'Clear the filter to see everything.' : 'Your next film night starts with a seat.'}</p>
          <Link className="btn" to="/">
            Find a film
          </Link>
        </EmptyState>
      )}
      {data && !loading && !!data.items.length && (
        <>
          <ul className="bookings" role="list">
            {data.items.map((b) => {
              const rp = refundPreview(b);
              return (
                <li key={b.id} className="booking">
                  <div className="booking__art" aria-hidden="true">
                    <GeneratedPoster title={b.show.movieTitle} />
                  </div>
                  <div className="booking__body">
                    <h2 className="booking__title">{b.show.movieTitle}</h2>
                    <p>
                      {b.show.cinemaName}, {b.show.screenName}
                    </p>
                    <p>
                      {formatLongDate(b.show.startsAt)} at {b.show.localTime}
                    </p>
                    <p>
                      Seats {sortSeatIds(b.seats.map((s) => s.id)).join(', ')}, {money(b.amount)}
                      {b.refundAmount > 0 && `, ${money(b.refundAmount)} refunded`}
                    </p>
                    <p className="mono booking__code">{b.code}</p>
                  </div>
                  <div className="booking__side">
                    <StatusBadge status={b.status} />
                    <div className="booking__actions">
                      {b.status === 'HELD' || b.status === 'PAYMENT_PENDING' ? (
                        <Link className="btn btn--sm" to={`/checkout/${b.id}`}>
                          Pay now
                        </Link>
                      ) : (
                        <Link className="btn btn--sm btn--ghost" to={`/bookings/${b.id}`}>
                          {b.status === 'CONFIRMED' ? 'View ticket' : 'Details'}
                          <span className="visually-hidden"> for {b.show.movieTitle}</span>
                        </Link>
                      )}
                      {rp.allowed && (
                        <button type="button" className="btn btn--sm btn--quiet" onClick={() => setConfirming(b)}>
                          Cancel<span className="visually-hidden"> booking {b.code}</span>
                        </button>
                      )}
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
          {data.totalPages > 1 && (
            <nav className="pager" aria-label="Pages">
              <button type="button" className="btn btn--ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>
                Previous
              </button>
              <span>
                Page {data.page} of {data.totalPages}
              </span>
              <button type="button" className="btn btn--ghost" disabled={page >= data.totalPages} onClick={() => setPage(page + 1)}>
                Next
              </button>
            </nav>
          )}
        </>
      )}
      {confirming && preview && (
        <ConfirmDialog
          title={`Cancel booking ${confirming.code}?`}
          confirmLabel="Cancel booking"
          cancelLabel="Keep booking"
          danger
          onConfirm={() => cancel(confirming)}
          onClose={() => setConfirming(null)}
        >
          <p>{preview.note}</p>
          <p className="refund-line">
            Refund <strong>{money(preview.amount)}</strong> of {money(confirming.amount)}
          </p>
        </ConfirmDialog>
      )}
    </div>
  );
}
