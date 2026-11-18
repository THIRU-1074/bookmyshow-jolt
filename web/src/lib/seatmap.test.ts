import { describe, expect, it } from 'vitest';
import { initSeats, moveFocus, navGrid, seatReducer, toggleOutcome, totalFor } from './seatmap';
import { sampleMap } from '../test/fixtures';

describe('seat selection', () => {
  it('toggles available seats and totals by category price', () => {
    let s = initSeats(sampleMap());
    s = seatReducer(s, { type: 'toggle', id: 'B1', max: 10 });
    s = seatReducer(s, { type: 'toggle', id: 'C3', max: 10 });
    expect(s.selected).toEqual(['B1', 'C3']);
    expect(totalFor(s)).toBe(40000);
    s = seatReducer(s, { type: 'toggle', id: 'B1', max: 10 });
    expect(s.selected).toEqual(['C3']);
  });

  it('refuses held, booked and blocked seats', () => {
    const s = initSeats(sampleMap({ B2: 'HELD', B3: 'BOOKED' }));
    for (const id of ['A1', 'B2', 'B3']) {
      expect(toggleOutcome(s, id, 10)).toBe('unavailable');
      expect(seatReducer(s, { type: 'toggle', id, max: 10 })).toBe(s);
    }
  });

  it('enforces the max-seats limit', () => {
    let s = initSeats(sampleMap());
    s = seatReducer(s, { type: 'toggle', id: 'B1', max: 2 });
    s = seatReducer(s, { type: 'toggle', id: 'B2', max: 2 });
    expect(toggleOutcome(s, 'B3', 2)).toBe('limit');
    expect(seatReducer(s, { type: 'toggle', id: 'B3', max: 2 }).selected).toEqual(['B1', 'B2']);
  });
});

describe('category key', () => {
  it('accepts the server category key as well as the spec code key', () => {
    const raw = sampleMap();
    const loose = { ...raw, categories: raw.categories.map(({ code, ...c }) => ({ ...c, category: code })) } as unknown as typeof raw;
    let s = initSeats(loose);
    s = seatReducer(s, { type: 'toggle', id: 'C1', max: 10 });
    expect(s.map.categories.map((c) => c.code)).toEqual(['CLASSIC', 'PRIME']);
    expect(totalFor(s)).toBe(25000);
  });
});

describe('live updates', () => {
  it('drops a selected seat when another buyer holds it and reports it as lost', () => {
    let s = initSeats(sampleMap());
    s = seatReducer(s, { type: 'toggle', id: 'C1', max: 10 });
    s = seatReducer(s, { type: 'toggle', id: 'C2', max: 10 });
    s = seatReducer(s, { type: 'patch', seats: ['C2', 'C4'], to: 'HELD' });
    expect(s.status.C2).toBe('HELD');
    expect(s.status.C4).toBe('HELD');
    expect(s.selected).toEqual(['C1']);
    expect(s.lost).toEqual(['C2']);
  });

  it('releases seats back to available without touching the selection', () => {
    let s = initSeats(sampleMap({ B4: 'HELD' }));
    s = seatReducer(s, { type: 'toggle', id: 'B1', max: 10 });
    s = seatReducer(s, { type: 'patch', seats: ['B4'], to: 'AVAILABLE' });
    expect(s.status.B4).toBe('AVAILABLE');
    expect(s.selected).toEqual(['B1']);
    expect(s.lost).toEqual([]);
  });

  it('ignores unknown and blocked seats in patches', () => {
    const s = initSeats(sampleMap());
    expect(seatReducer(s, { type: 'patch', seats: ['Z9', 'A1'], to: 'BOOKED' })).toBe(s);
  });

  it('applies a reconnect snapshot and keeps only selections still available', () => {
    let s = initSeats(sampleMap());
    s = seatReducer(s, { type: 'toggle', id: 'B1', max: 10 });
    s = seatReducer(s, { type: 'toggle', id: 'B2', max: 10 });
    s = seatReducer(s, { type: 'snapshot', map: sampleMap({ B2: 'BOOKED' }) });
    expect(s.selected).toEqual(['B1']);
    expect(s.lost).toEqual(['B2']);
    expect(s.status.B2).toBe('BOOKED');
  });
});

describe('keyboard movement', () => {
  const grid = navGrid(sampleMap());

  it('skips blocked seats', () => {
    expect(grid[0]).toEqual(['A2', 'A3', 'A4']);
  });

  it('moves within a row and stops at the ends', () => {
    expect(moveFocus(grid, 'B1', 'ArrowRight')).toBe('B2');
    expect(moveFocus(grid, 'B1', 'ArrowLeft')).toBe('B1');
    expect(moveFocus(grid, 'B2', 'End')).toBe('B4');
    expect(moveFocus(grid, 'B4', 'Home')).toBe('B1');
  });

  it('moves between rows keeping the relative position', () => {
    expect(moveFocus(grid, 'B4', 'ArrowDown')).toBe('C6');
    expect(moveFocus(grid, 'B1', 'ArrowUp')).toBe('A2');
    expect(moveFocus(grid, 'C6', 'ArrowDown')).toBe('C6');
  });

  it('ignores other keys', () => {
    expect(moveFocus(grid, 'B1', 'Enter')).toBeNull();
  });
});
