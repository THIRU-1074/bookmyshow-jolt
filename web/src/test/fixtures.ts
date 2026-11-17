import type { SeatMap } from '../api/types';

export function sampleMap(over: Partial<Record<string, SeatMap['rows'][number]['seats'][number]['status']>> = {}): SeatMap {
  const row = (r: string, n: number, cat: string, blocked: number[] = []) => ({
    row: r,
    aisleAfter: [2],
    seats: Array.from({ length: n }, (_, i) => {
      const id = `${r}${i + 1}`;
      return { id, number: i + 1, category: cat, status: over[id] ?? (blocked.includes(i + 1) ? 'BLOCKED' : 'AVAILABLE') } as const;
    }),
  });
  return {
    showId: 7,
    version: 'v1',
    categories: [
      { code: 'CLASSIC', name: 'Classic', amount: 15000 },
      { code: 'PRIME', name: 'Prime', amount: 25000 },
    ],
    rows: [row('A', 4, 'CLASSIC', [1]), row('B', 4, 'CLASSIC'), row('C', 6, 'PRIME')],
    holdTtlSeconds: 420,
  };
}
