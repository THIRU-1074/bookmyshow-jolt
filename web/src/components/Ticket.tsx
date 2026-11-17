import type { Booking } from '../api/types';
import { formatLongDate, money, sortSeatIds } from '../lib/format';
import { CodeMatrix } from './bits';
import { GeneratedPoster } from './Poster';

/**
 * The confirmed ticket: a stub with the film art and a perforated tear line to the entry code.
 * It prints on its own (see the print stylesheet), so it doubles as the paper ticket.
 */
export function Ticket({ booking: b, reveal = false }: { booking: Booking; reveal?: boolean }) {
  const seats = sortSeatIds(b.seats.map((s) => s.id));
  return (
    <article className={`ticket${reveal ? ' ticket--reveal' : ''}`} aria-label={`Ticket ${b.code}`}>
      <div className="ticket__main">
        <div className="ticket__art" aria-hidden="true">
          <GeneratedPoster title={b.show.movieTitle} />
        </div>
        <div className="ticket__info">
          <p className="ticket__brand">Houselights admit {seats.length}</p>
          <h2 className="ticket__title">{b.show.movieTitle}</h2>
          <dl className="ticket__grid">
            <div>
              <dt>Date</dt>
              <dd>{formatLongDate(b.show.startsAt)}</dd>
            </div>
            <div>
              <dt>Time</dt>
              <dd>{b.show.localTime}</dd>
            </div>
            <div>
              <dt>Cinema</dt>
              <dd>{b.show.cinemaName}</dd>
            </div>
            <div>
              <dt>Screen</dt>
              <dd>{b.show.screenName}</dd>
            </div>
            <div className="ticket__wide">
              <dt>Seats</dt>
              <dd className="ticket__seats">{seats.join(' ')}</dd>
            </div>
            <div>
              <dt>Paid</dt>
              <dd>{money(b.amount)}</dd>
            </div>
          </dl>
        </div>
      </div>
      <div className="ticket__tear" aria-hidden="true" />
      <div className="ticket__stub">
        <CodeMatrix code={b.code} />
        <p className="ticket__code">
          <span className="visually-hidden">Booking code </span>
          {b.code}
        </p>
        <p className="ticket__hint">Show this code at the door</p>
      </div>
    </article>
  );
}
