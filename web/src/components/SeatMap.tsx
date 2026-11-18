import { memo, useCallback, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { SeatStatus } from '../api/types';
import { money, moneySpoken } from '../lib/format';
import { moveFocus, navGrid, STATUS_TEXT, type SeatState } from '../lib/seatmap';

interface Props {
  state: SeatState;
  onToggle: (id: string) => void;
  /** Category codes in display order, used for the colour band of each row. */
  categoryOrder: string[];
}

/**
 * The auditorium. Seats are buttons in a roving-tabindex grid: Tab enters once, arrow keys move,
 * Home/End jump within a row, Enter or Space toggles. Status is carried by shape and an icon as
 * well as colour (held seats are hatched, booked seats are crossed, selected seats are ticked).
 */
export function SeatMap({ state, onToggle, categoryOrder }: Props) {
  const grid = useMemo(() => navGrid(state.map), [state.map]);
  const [focusId, setFocusId] = useState<string | null>(null);
  const refs = useRef(new Map<string, HTMLButtonElement>());
  const price = useMemo(() => new Map(state.map.categories.map((c) => [c.code, c])), [state.map.categories]);
  const selected = useMemo(() => new Set(state.selected), [state.selected]);
  const active = focusId && state.status[focusId] && state.status[focusId] !== 'BLOCKED' ? focusId : (grid[0]?.[0] ?? null);

  const onKeyDown = useCallback(
    (e: KeyboardEvent<HTMLDivElement>) => {
      const id = (e.target as HTMLElement).dataset.seat;
      if (!id) return;
      const next = moveFocus(grid, id, e.key);
      if (!next) return;
      e.preventDefault();
      setFocusId(next);
      refs.current.get(next)?.focus();
    },
    [grid],
  );

  const label = (id: string, status: SeatStatus) => {
    const info = state.info[id];
    if (!info) return id;
    const cat = price.get(info.category);
    const base = `Row ${info.row} seat ${info.number}, ${cat?.name ?? info.category}, ${moneySpoken(cat?.amount ?? 0)}`;
    return selected.has(id) ? `${base}, selected` : `${base}, ${STATUS_TEXT[status]}`;
  };

  return (
    <div className="seatmap" role="group" aria-label="Seat map, screen at the top" onKeyDown={onKeyDown}>
      {state.map.rows.map((r) => {
        const aisles = new Set(r.aisleAfter);
        const tier = Math.max(0, categoryOrder.indexOf(r.seats[0]?.category ?? ''));
        return (
          <div key={r.row} className={`seatrow seatrow--tier${tier % 4}`} role="group" aria-label={`Row ${r.row}`}>
            <span className="seatrow__label" aria-hidden="true">
              {r.row}
            </span>
            <div className="seatrow__seats">
              {r.seats.map((s) => {
                const st = state.status[s.id] ?? s.status;
                return (
                  <SeatCell
                    key={s.id}
                    id={s.id}
                    number={s.number}
                    status={st}
                    picked={selected.has(s.id)}
                    tabbable={s.id === active}
                    aisle={aisles.has(s.number)}
                    label={label(s.id, st)}
                    onToggle={onToggle}
                    onFocus={setFocusId}
                    refs={refs.current}
                  />
                );
              })}
            </div>
            <span className="seatrow__label" aria-hidden="true">
              {r.row}
            </span>
          </div>
        );
      })}
    </div>
  );
}

interface CellProps {
  id: string;
  number: number;
  status: SeatStatus;
  picked: boolean;
  tabbable: boolean;
  aisle: boolean;
  label: string;
  onToggle: (id: string) => void;
  onFocus: (id: string) => void;
  refs: Map<string, HTMLButtonElement>;
}

const SeatCell = memo(function SeatCell({ id, number, status, picked, tabbable, aisle, label, onToggle, onFocus, refs }: CellProps) {
  if (status === 'BLOCKED') {
    return (
      <>
        <span className="seat seat--blocked" aria-hidden="true" />
        {aisle && <span className="aisle" aria-hidden="true" />}
      </>
    );
  }
  const taken = status !== 'AVAILABLE' && !picked;
  return (
    <>
      <button
        ref={(el) => {
          if (el) refs.set(id, el);
          else refs.delete(id);
        }}
        type="button"
        data-seat={id}
        className={`seat seat--${status.toLowerCase()}${picked ? ' seat--picked' : ''}`}
        aria-pressed={picked}
        aria-disabled={taken || undefined}
        aria-label={label}
        tabIndex={tabbable ? 0 : -1}
        onClick={() => onToggle(id)}
        onFocus={() => onFocus(id)}
      >
        <span className="seat__num" aria-hidden="true">
          {picked ? '✓' : number}
        </span>
      </button>
      {aisle && <span className="aisle" aria-hidden="true" />}
    </>
  );
});

export function SeatLegend({ categories }: { categories: { code: string; name: string; amount: number }[] }) {
  return (
    <div className="legend">
      <ul className="legend__states" aria-label="Seat key">
        <li>
          <span className="seat seat--available seat--demo" aria-hidden="true" /> Available
        </li>
        <li>
          <span className="seat seat--available seat--picked seat--demo" aria-hidden="true">
            <span className="seat__num">✓</span>
          </span>{' '}
          Your pick
        </li>
        <li>
          <span className="seat seat--held seat--demo" aria-hidden="true" /> Held by someone
        </li>
        <li>
          <span className="seat seat--booked seat--demo" aria-hidden="true" /> Sold
        </li>
      </ul>
      <ul className="legend__prices" aria-label="Prices by seat category">
        {categories.map((c, i) => (
          <li key={c.code}>
            <span className={`tier-swatch tier-swatch--${i % 4}`} aria-hidden="true" />
            <span className="legend__cat">{c.name}</span>
            <span className="legend__amt">{money(c.amount)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
