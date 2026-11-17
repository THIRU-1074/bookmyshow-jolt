import { useCallback, useEffect, useReducer, useRef, useState, type CSSProperties } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, newIdempotencyKey } from '../api/client';
import type { Booking, Hold, SeatMap as SeatMapData, ShowDetail } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Badge, PageTitle } from '../components/bits';
import { SeatLegend, SeatMap } from '../components/SeatMap';
import { ErrorState, LoadingBlock, Skeleton } from '../components/States';
import { useToast } from '../components/Toasts';
import { useCountdown } from '../hooks/useCountdown';
import { useSeatEvents, type LiveState } from '../hooks/useSeatEvents';
import { formatLongDate, languageName, mmss, money, sortSeatIds } from '../lib/format';
import { initSeats, seatReducer, toggleOutcome, totalFor, type SeatState } from '../lib/seatmap';

export const MAX_SEATS = 10;
const ZOOMS = [0.7, 0.85, 1, 1.2, 1.45];

export function ShowSeats() {
  const { id } = useParams();
  const showId = Number(id);
  const [show, setShow] = useState<ShowDetail>();
  const [initial, setInitial] = useState<SeatMapData>();
  const [error, setError] = useState<Error>();
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError(undefined);
    Promise.all([
      api.get<ShowDetail>(`/api/shows/${showId}`, undefined, ctrl.signal),
      api.get<SeatMapData>(`/api/shows/${showId}/seats`, undefined, ctrl.signal),
    ])
      .then(([s, m]) => {
        setShow(s);
        setInitial(m);
      })
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setError(e);
      });
    return () => ctrl.abort();
  }, [showId, attempt]);

  if (error) {
    return (
      <div className="wrap section">
        <PageTitle>Choose seats</PageTitle>
        <ErrorState error={error} onRetry={() => setAttempt((a) => a + 1)} title="The seat map did not load" />
      </div>
    );
  }
  if (!show || !initial) {
    return (
      <div className="wrap section">
        <LoadingBlock label="Loading seat map">
          <Skeleton className="skeleton--title" />
          <Skeleton className="skeleton--line short" />
          <Skeleton className="skeleton--map" />
        </LoadingBlock>
      </div>
    );
  }
  return <Auditorium show={show} initial={initial} />;
}

function Auditorium({ show, initial }: { show: ShowDetail; initial: SeatMapData }) {
  const { user } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const [state, dispatch] = useReducer(seatReducer, initial, initSeats);
  const [hold, setHold] = useState<Hold | null>(null);
  const [busy, setBusy] = useState<'hold' | 'book' | 'release' | null>(null);
  const [zoom, setZoom] = useState(2);
  const [announce, setAnnounce] = useState('');
  const holdKey = useRef<{ sig: string; key: string } | null>(null);
  const holdRef = useRef<Hold | null>(null);
  holdRef.current = hold;
  const cancelled = show.status === 'CANCELLED';

  const live: LiveState = useSeatEvents(
    show.id,
    {
      onSnapshot: (map) => dispatch({ type: 'snapshot', map }),
      onPatch: (seats, to) => {
        // Our own hold comes back as seat-held; it is not someone else taking our seats.
        const mine = new Set(holdRef.current?.seats ?? []);
        dispatch({ type: 'patch', seats: seats.filter((s) => !(to === 'HELD' && mine.has(s))), to });
      },
    },
    !cancelled,
  );

  // Tell the buyer when a live update takes seats out of their selection.
  useEffect(() => {
    if (state.lost.length) {
      const msg = `Seat ${state.lost.join(', ')} was just taken by someone else and removed from your selection.`;
      setAnnounce(msg);
      toast.show(msg, 'error');
    }
  }, [state.lost, toast]);

  const stateRef = useRef<SeatState>(state);
  stateRef.current = state;

  const onToggle = useCallback(
    (seat: string) => {
      if (holdRef.current) {
        toast.show('Release your held seats to change them.', 'info');
        return;
      }
      const outcome = toggleOutcome(stateRef.current, seat, MAX_SEATS);
      if (outcome === 'limit') toast.show(`You can pick up to ${MAX_SEATS} seats at a time.`, 'info');
      else if (outcome === 'unavailable') toast.show(`Seat ${seat} is not available.`, 'info');
      dispatch({ type: 'toggle', id: seat, max: MAX_SEATS });
    },
    [toast],
  );
  const selected = hold ? sortSeatIds(hold.seats) : sortSeatIds(state.selected);
  const total = hold ? hold.amount : totalFor(state);
  const categoryOrder = state.map.categories.map((c) => c.code);
  const byCategory = state.map.categories
    .map((c) => ({ ...c, count: selected.filter((s) => state.info[s]?.category === c.code).length }))
    .filter((c) => c.count);

  async function holdSeats() {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(location.pathname)}`);
      return;
    }
    const seats = sortSeatIds(state.selected);
    const sig = seats.join(',');
    // Same seats, same key: a retry after a timeout replays instead of holding twice.
    if (holdKey.current?.sig !== sig) holdKey.current = { sig, key: newIdempotencyKey() };
    setBusy('hold');
    try {
      const h = await api.post<Hold>('/api/holds', { showId: show.id, seats }, holdKey.current.key);
      setHold(h);
      dispatch({ type: 'clear' });
      setAnnounce(`Seats ${seats.join(', ')} are held for you.`);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'SEAT_UNAVAILABLE') {
        dispatch({ type: 'patch', seats: e.conflicts, to: 'HELD' });
        holdKey.current = null;
      } else if (e instanceof ApiError && e.status === 401) {
        navigate(`/login?next=${encodeURIComponent(location.pathname)}`);
      } else {
        toast.error(e);
      }
    } finally {
      setBusy(null);
    }
  }

  async function continueToPay() {
    if (!hold) return;
    setBusy('book');
    try {
      const b = await api.post<Booking>('/api/bookings', { holdId: hold.id });
      navigate(`/checkout/${b.id}`);
    } catch (e) {
      toast.error(e);
      if (e instanceof ApiError && (e.status === 410 || e.status === 409)) setHold(null);
      setBusy(null);
    }
  }

  async function release() {
    if (!hold) return;
    setBusy('release');
    try {
      await api.del(`/api/holds/${hold.id}`);
    } catch {
      /* already gone: expired or consumed */
    }
    setHold(null);
    holdKey.current = null;
    dispatch({ type: 'clear' });
    setBusy(null);
  }

  const left = useCountdown(hold?.expiresAt);
  useEffect(() => {
    if (left === 0 && hold) {
      setHold(null);
      dispatch({ type: 'clear' });
      toast.show('Your hold ran out and the seats went back on sale.', 'error');
    }
  }, [left, hold, toast]);

  const mapStyle = { '--zoom': ZOOMS[zoom] } as CSSProperties;

  return (
    <div className="seats-page">
      <div className="wrap seats-head">
        <Link to={`/movies/${show.movie.id}`} className="back-link">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M15 5l-7 7 7 7" />
          </svg>
          All showtimes
        </Link>
        <PageTitle className="seats-head__title">{show.movie.title}</PageTitle>
        <p className="seats-head__meta">
          <span>
            {show.cinema.name}, {show.screen.name}
          </span>
          <span>
            {formatLongDate(show.startsAt)} at {show.localTime}
          </span>
          <Badge tone="format">{show.format}</Badge>
          <Badge tone="lang">{languageName(show.language)}</Badge>
          <Badge tone="cert">{show.movie.certificate}</Badge>
        </p>
        <p className={`live live--${live}`} role="status">
          <span className="live__dot" aria-hidden="true" />
          {cancelled
            ? 'This show was cancelled.'
            : live === 'live'
              ? 'Live: seats update as others book'
              : live === 'reconnecting'
                ? 'Reconnecting to live updates'
                : 'Connecting to live updates'}
        </p>
      </div>

      <div className="wrap auditorium">
        <div className="screen-cue" aria-hidden="true">
          <svg viewBox="0 0 600 60" preserveAspectRatio="none">
            <defs>
              <linearGradient id="beam" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0" className="beam-top" />
                <stop offset="1" className="beam-bottom" />
              </linearGradient>
            </defs>
            <path d="M20 40 Q300 0 580 40 L560 60 Q300 26 40 60 Z" fill="url(#beam)" />
            <path d="M20 40 Q300 0 580 40" className="screen-cue__line" />
          </svg>
          <span>Screen this way</span>
        </div>
        <div className="zoom" role="group" aria-label="Zoom seat map">
          <button type="button" className="icon-btn icon-btn--sm" onClick={() => setZoom((z) => Math.max(0, z - 1))} disabled={zoom === 0} aria-label="Zoom out">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 12h12" />
            </svg>
          </button>
          <button type="button" className="icon-btn icon-btn--sm" onClick={() => setZoom(2)} aria-label={`${Math.round((ZOOMS[zoom] ?? 1) * 100)}%, reset zoom`}>
            {Math.round((ZOOMS[zoom] ?? 1) * 100)}%
          </button>
          <button
            type="button"
            className="icon-btn icon-btn--sm"
            onClick={() => setZoom((z) => Math.min(ZOOMS.length - 1, z + 1))}
            disabled={zoom === ZOOMS.length - 1}
            aria-label="Zoom in"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 12h12M12 6v12" />
            </svg>
          </button>
        </div>
        <div className="seatmap-scroll" style={mapStyle}>
          <SeatMap state={withHold(state, hold)} onToggle={onToggle} categoryOrder={categoryOrder} />
        </div>
        <p className="visually-hidden" aria-live="polite">
          {announce}
        </p>
        <SeatLegend categories={state.map.categories} />
        <p className="hint">
          Use arrow keys to move between seats and Enter to pick. Up to {MAX_SEATS} seats; we hold them for{' '}
          {Math.round(state.map.holdTtlSeconds / 60)} minutes while you pay.
        </p>
      </div>

      <aside className={`summary-bar${selected.length ? ' is-open' : ''}`} aria-label="Your selection">
        <div className="wrap summary-bar__inner">
          <div className="summary-bar__seats">
            {selected.length ? (
              <>
                <p className="summary-bar__count">
                  {selected.length} {selected.length === 1 ? 'seat' : 'seats'}
                  <span className="summary-bar__cats">
                    {byCategory.map((c) => `${c.count} ${c.name}`).join(', ')}
                  </span>
                </p>
                <p className="summary-bar__ids">{selected.join(' ')}</p>
              </>
            ) : (
              <p className="summary-bar__count">Pick your seats</p>
            )}
          </div>
          {hold && left !== null && (
            <p className={`summary-bar__timer${left < 60 ? ' is-urgent' : ''}`}>
              <span className="visually-hidden">Hold time left </span>
              <span aria-hidden="true">Held </span>
              <time>{mmss(left)}</time>
            </p>
          )}
          <p className="summary-bar__total">
            <span className="visually-hidden">Total </span>
            {money(total)}
          </p>
          <div className="summary-bar__actions">
            {hold ? (
              <>
                <button type="button" className="btn btn--quiet" onClick={release} disabled={busy !== null}>
                  Release
                </button>
                <button type="button" className="btn" onClick={continueToPay} disabled={busy !== null}>
                  {busy === 'book' ? 'Preparing' : 'Continue to payment'}
                </button>
              </>
            ) : (
              <button type="button" className="btn" onClick={holdSeats} disabled={!selected.length || busy !== null || cancelled}>
                {busy === 'hold' ? 'Holding' : user ? 'Hold seats' : 'Sign in to hold seats'}
              </button>
            )}
          </div>
        </div>
      </aside>
    </div>
  );
}

/** While a hold is active, its seats render as the buyer's picks rather than as taken. */
function withHold(state: SeatState, hold: Hold | null): SeatState {
  if (!hold) return state;
  const status = { ...state.status };
  for (const s of hold.seats) status[s] = 'AVAILABLE';
  return { ...state, status, selected: hold.seats };
}
