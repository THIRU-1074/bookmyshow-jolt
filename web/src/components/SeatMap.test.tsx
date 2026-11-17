import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useReducer } from 'react';
import { describe, expect, it } from 'vitest';
import { useSeatEvents } from '../hooks/useSeatEvents';
import { initSeats, seatReducer } from '../lib/seatmap';
import { sampleMap } from '../test/fixtures';
import { SeatMap } from './SeatMap';

function Harness({ max = 10 }: { max?: number }) {
  const [state, dispatch] = useReducer(seatReducer, sampleMap({ B3: 'BOOKED' }), initSeats);
  return (
    <>
      <SeatMap state={state} categoryOrder={['CLASSIC', 'PRIME']} onToggle={(id) => dispatch({ type: 'toggle', id, max })} />
      <output data-testid="sel">{state.selected.join(',')}</output>
    </>
  );
}

describe('SeatMap', () => {
  it('labels seats for screen readers with row, number, category, price and status', () => {
    render(<Harness />);
    expect(screen.getByRole('button', { name: 'Row C seat 2, Prime, 250 rupees, available' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Row B seat 3, Classic, 150 rupees, booked' })).toHaveAttribute('aria-disabled', 'true');
    // Blocked seats are gaps, not buttons.
    expect(screen.queryByRole('button', { name: /Row A seat 1,/ })).toBeNull();
  });

  it('is one tab stop; arrows move and Enter or Space toggles', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.tab();
    expect(document.activeElement).toHaveAttribute('data-seat', 'A2');
    await user.keyboard('{ArrowDown}{ArrowRight}');
    expect(document.activeElement).toHaveAttribute('data-seat', 'B2');
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('sel')).toHaveTextContent('B2');
    expect(document.activeElement).toHaveAttribute('aria-pressed', 'true');
    expect(document.activeElement?.getAttribute('aria-label')).toMatch(/, selected$/);
    await user.keyboard('{ArrowRight} ');
    // B3 is booked: Space does nothing.
    expect(screen.getByTestId('sel')).toHaveTextContent(/^B2$/);
    const tabbable = document.querySelectorAll('.seat[tabindex="0"]');
    expect(tabbable).toHaveLength(1);
  });

  it('respects the max-seats limit', async () => {
    const user = userEvent.setup();
    render(<Harness max={2} />);
    await user.click(screen.getByRole('button', { name: /Row C seat 1,/ }));
    await user.click(screen.getByRole('button', { name: /Row C seat 2,/ }));
    await user.click(screen.getByRole('button', { name: /Row C seat 3,/ }));
    expect(screen.getByTestId('sel')).toHaveTextContent(/^C1,C2$/);
  });
});

class FakeSource {
  static last: FakeSource | null = null;
  static readonly CLOSED = 2;
  readyState = 1;
  closed = false;
  listeners = new Map<string, ((e: Event) => void)[]>();
  constructor(public url: string) {
    FakeSource.last = this;
  }
  addEventListener(type: string, fn: (e: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  emit(type: string, data?: unknown) {
    const e = new MessageEvent(type, { data: data === undefined ? undefined : JSON.stringify(data) });
    for (const fn of this.listeners.get(type) ?? []) fn(e);
  }
  close() {
    this.closed = true;
  }
}

function Live() {
  const [state, dispatch] = useReducer(seatReducer, sampleMap(), initSeats);
  const live = useSeatEvents(
    7,
    {
      onSnapshot: (map) => dispatch({ type: 'snapshot', map }),
      onPatch: (seats, to) => dispatch({ type: 'patch', seats, to }),
    },
    true,
    (url) => new FakeSource(url) as unknown as EventSource,
  );
  return (
    <>
      <p data-testid="live">{live}</p>
      <SeatMap state={state} categoryOrder={['CLASSIC', 'PRIME']} onToggle={(id) => dispatch({ type: 'toggle', id, max: 10 })} />
    </>
  );
}

describe('useSeatEvents', () => {
  it('applies snapshot, held, released and booked events and reconnect snapshots', () => {
    (globalThis as unknown as { EventSource: unknown }).EventSource ??= FakeSource;
    const { unmount } = render(<Live />);
    const src = FakeSource.last!;
    expect(src.url).toBe('/api/shows/7/events');
    act(() => src.emit('snapshot', sampleMap({ C1: 'BOOKED' })));
    expect(screen.getByTestId('live')).toHaveTextContent('live');
    expect(screen.getByRole('button', { name: /Row C seat 1, .*booked/ })).toBeInTheDocument();
    act(() => src.emit('seat-held', { showId: 7, seats: ['C2'], until: '2026-11-20T13:07:00Z' }));
    expect(screen.getByRole('button', { name: /Row C seat 2, .*held by another buyer/ })).toBeInTheDocument();
    act(() => src.emit('seat-released', { showId: 7, seats: ['C2'], reason: 'EXPIRED' }));
    expect(screen.getByRole('button', { name: /Row C seat 2, .*available/ })).toBeInTheDocument();
    act(() => src.emit('seat-booked', { showId: 7, seats: ['C3'] }));
    expect(screen.getByRole('button', { name: /Row C seat 3, .*booked/ })).toBeInTheDocument();
    act(() => src.emit('error'));
    expect(screen.getByTestId('live')).toHaveTextContent('reconnecting');
    act(() => src.emit('snapshot', sampleMap()));
    expect(screen.getByRole('button', { name: /Row C seat 3, .*available/ })).toBeInTheDocument();
    act(() => src.emit('heartbeat', { ts: 'x' }));
    unmount();
    expect(src.closed).toBe(true);
  });
});
