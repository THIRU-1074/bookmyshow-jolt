// Pure seat-map logic: applying the SSE snapshot and patches, selection rules and keyboard
// movement. Kept free of React so it is unit tested directly.
import type { SeatMap, SeatStatus } from '../api/types';

export interface SeatInfo {
  id: string;
  row: string;
  number: number;
  category: string;
}

export interface SeatState {
  map: SeatMap;
  status: Record<string, SeatStatus>;
  info: Record<string, SeatInfo>;
  selected: string[];
  /** Seats removed from the selection by the last change because someone else took them. */
  lost: string[];
}

export type SeatAction =
  | { type: 'snapshot'; map: SeatMap }
  | { type: 'patch'; seats: string[]; to: SeatStatus }
  | { type: 'toggle'; id: string; max: number }
  | { type: 'clear' };

export type ToggleResult = 'selected' | 'deselected' | 'unavailable' | 'limit';

/**
 * SPEC section 7 names the category key `code`, but the server sends `category` (as in show
 * prices). Accept either so the map works against both.
 */
export function normalizeSeatMap(raw: SeatMap): SeatMap {
  const categories = (raw.categories ?? []).map((c) => {
    const loose = c as { code?: string; category?: string; name: string; amount: number };
    return { code: loose.code ?? loose.category ?? '', name: loose.name, amount: loose.amount };
  });
  return { ...raw, categories };
}

export function initSeats(raw: SeatMap): SeatState {
  const map = normalizeSeatMap(raw);
  const status: Record<string, SeatStatus> = {};
  const info: Record<string, SeatInfo> = {};
  for (const r of map.rows) {
    for (const s of r.seats) {
      status[s.id] = s.status;
      info[s.id] = { id: s.id, row: r.row, number: s.number, category: s.category };
    }
  }
  return { map, status, info, selected: [], lost: [] };
}

export function toggleOutcome(state: SeatState, id: string, max: number): ToggleResult {
  if (state.selected.includes(id)) return 'deselected';
  if (state.status[id] !== 'AVAILABLE') return 'unavailable';
  if (state.selected.length >= max) return 'limit';
  return 'selected';
}

export function seatReducer(state: SeatState, action: SeatAction): SeatState {
  switch (action.type) {
    case 'snapshot': {
      // A reconnect snapshot replaces everything; keep only selections still available.
      const next = initSeats(action.map);
      const kept = state.selected.filter((id) => next.status[id] === 'AVAILABLE');
      return { ...next, selected: kept, lost: state.selected.filter((id) => !kept.includes(id)) };
    }
    case 'patch': {
      const status = { ...state.status };
      let touched = false;
      for (const id of action.seats) {
        if (!(id in status) || status[id] === 'BLOCKED') continue;
        status[id] = action.to;
        touched = true;
      }
      if (!touched) return state;
      const lost = action.to === 'AVAILABLE' ? [] : state.selected.filter((id) => action.seats.includes(id));
      return { ...state, status, selected: state.selected.filter((id) => !lost.includes(id)), lost };
    }
    case 'toggle': {
      const outcome = toggleOutcome(state, action.id, action.max);
      if (outcome === 'deselected') return { ...state, selected: state.selected.filter((s) => s !== action.id), lost: [] };
      if (outcome === 'selected') return { ...state, selected: [...state.selected, action.id], lost: [] };
      return state;
    }
    case 'clear':
      return { ...state, selected: [], lost: [] };
  }
}

export function totalFor(state: SeatState): number {
  const price = new Map(state.map.categories.map((c) => [c.code, c.amount]));
  return state.selected.reduce((sum, id) => sum + (price.get(state.info[id]?.category ?? '') ?? 0), 0);
}

/** Navigable seats per row: blocked seats are gaps, not stops. */
export function navGrid(map: SeatMap): string[][] {
  return map.rows.map((r) => r.seats.filter((s) => s.status !== 'BLOCKED').map((s) => s.id)).filter((r) => r.length);
}

/**
 * Next focus target for a key press, or null when the key is not a movement. Up and down keep
 * the seat's horizontal position as a fraction of the row so wide and narrow rows line up.
 */
export function moveFocus(grid: string[][], current: string, key: string): string | null {
  const ri = grid.findIndex((r) => r.includes(current));
  if (ri < 0) return null;
  const row = grid[ri] as string[];
  const ci = row.indexOf(current);
  const across = (target: string[]) => {
    const frac = row.length > 1 ? ci / (row.length - 1) : 0;
    return target[Math.round(frac * (target.length - 1))] as string;
  };
  switch (key) {
    case 'ArrowRight':
      return row[Math.min(ci + 1, row.length - 1)] ?? null;
    case 'ArrowLeft':
      return row[Math.max(ci - 1, 0)] ?? null;
    case 'ArrowDown':
      return grid[ri + 1] ? across(grid[ri + 1] as string[]) : current;
    case 'ArrowUp':
      return grid[ri - 1] ? across(grid[ri - 1] as string[]) : current;
    case 'Home':
      return row[0] ?? null;
    case 'End':
      return row[row.length - 1] ?? null;
    default:
      return null;
  }
}

export const STATUS_TEXT: Record<SeatStatus, string> = {
  AVAILABLE: 'available',
  HELD: 'held by another buyer',
  BOOKED: 'booked',
  BLOCKED: 'not for sale',
};
