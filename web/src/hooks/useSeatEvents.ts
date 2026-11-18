// Live seat updates over SSE (SPEC section 7, "Live seat updates"). The server sends a snapshot
// on every (re)connect, so a dropped stream heals itself: EventSource reconnects after the
// server's `retry: 3000`, and the fresh snapshot replaces whatever was missed.
import { useEffect, useRef, useState } from 'react';
import type { SeatMap, SeatStatus } from '../api/types';

export type LiveState = 'connecting' | 'live' | 'reconnecting' | 'closed';

export interface SeatEventHandlers {
  onSnapshot: (map: SeatMap) => void;
  onPatch: (seats: string[], to: SeatStatus) => void;
}

type SourceFactory = (url: string) => EventSource;

export function useSeatEvents(
  showId: number | null,
  handlers: SeatEventHandlers,
  enabled = true,
  factory: SourceFactory = (url) => new EventSource(url),
): LiveState {
  const [state, setState] = useState<LiveState>('connecting');
  // Handlers change every render; the stream must not reconnect because of that.
  const ref = useRef(handlers);
  ref.current = handlers;

  useEffect(() => {
    if (showId === null || !enabled) {
      setState('closed');
      return;
    }
    setState('connecting');
    const source = factory(`/api/shows/${showId}/events`);
    const parse = (e: MessageEvent) => {
      try {
        return JSON.parse(e.data as string) as { seats?: string[] } & SeatMap;
      } catch {
        return null;
      }
    };
    const patch = (to: SeatStatus) => (e: Event) => {
      const data = parse(e as MessageEvent);
      if (data?.seats && Array.isArray(data.seats)) ref.current.onPatch(data.seats as string[], to);
    };
    const onOpen = () => setState('live');
    const onError = () => setState(source.readyState === EventSource.CLOSED ? 'closed' : 'reconnecting');
    const onSnapshot = (e: Event) => {
      const data = parse(e as MessageEvent);
      if (data && Array.isArray(data.rows)) {
        setState('live');
        ref.current.onSnapshot(data);
      }
    };
    const held = patch('HELD');
    const released = patch('AVAILABLE');
    const booked = patch('BOOKED');
    source.addEventListener('open', onOpen);
    source.addEventListener('error', onError);
    source.addEventListener('snapshot', onSnapshot);
    source.addEventListener('seat-held', held);
    source.addEventListener('seat-released', released);
    source.addEventListener('seat-booked', booked);
    return () => {
      source.close();
    };
    // factory is a test seam; treat it as stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showId, enabled]);

  return state;
}
