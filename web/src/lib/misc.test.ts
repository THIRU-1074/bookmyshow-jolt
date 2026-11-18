import { describe, expect, it } from 'vitest';
import { checkLayout } from '../components/manage/LayoutEditor';
import { safeNext } from '../pages/Auth';
import { codeMatrix, posterSpec, titleLines, usablePosterUrl } from './art';
import { mmss, money, refundPreview, zonedToUtc } from './format';
import { canUseOwnerDashboard, isAdmin, isAudience } from './roles';

const H = 3_600_000;
const booking = (status: 'CONFIRMED' | 'HELD' | 'PAYMENT_PENDING', hoursAhead: number) => ({
  status,
  amount: 50001,
  show: { id: 1, movieTitle: 'x', cinemaName: 'y', screenName: 'z', localTime: '10:00', startsAt: new Date(1_000_000_000_000 + hoursAhead * H).toISOString() },
});

describe('refundPreview (SPEC section 4)', () => {
  const now = 1_000_000_000_000;
  it('follows the time bands', () => {
    expect(refundPreview(booking('CONFIRMED', 30), now)).toMatchObject({ allowed: true, amount: 50001 });
    expect(refundPreview(booking('CONFIRMED', 24), now)).toMatchObject({ allowed: true, amount: 25000 });
    expect(refundPreview(booking('CONFIRMED', 4), now)).toMatchObject({ allowed: true, amount: 25000 });
    expect(refundPreview(booking('CONFIRMED', 3), now)).toMatchObject({ allowed: true, amount: 0 });
    expect(refundPreview(booking('CONFIRMED', 0), now)).toMatchObject({ allowed: false });
    expect(refundPreview(booking('HELD', 1), now)).toMatchObject({ allowed: true, amount: 0 });
    expect(refundPreview(booking('PAYMENT_PENDING', 30), now)).toMatchObject({ allowed: false });
  });
});

describe('formatting', () => {
  it('formats paise as rupees and countdowns as mm:ss', () => {
    expect(money(45000)).toBe('₹450');
    expect(money(45050)).toBe('₹450.50');
    expect(mmss(419)).toBe('06:59');
    expect(mmss(-3)).toBe('00:00');
  });
  it('converts cinema-local wall time to UTC', () => {
    expect(zonedToUtc('2026-11-20T19:00', 'Asia/Kolkata')).toBe('2026-11-20T13:30:00Z');
  });
});

describe('roles', () => {
  const u = (roles: string[]) => ({ id: 1, email: 'a', name: 'a', roles });
  it('treats USER and AUDIENCE alike and gates dashboards', () => {
    expect(isAudience(u(['USER']))).toBe(true);
    expect(isAudience(u(['AUDIENCE']))).toBe(true);
    expect(canUseOwnerDashboard(u(['AUDIENCE']))).toBe(false);
    expect(canUseOwnerDashboard(u(['AUDIENCE', 'THEATRE_OWNER']))).toBe(true);
    expect(canUseOwnerDashboard(u(['AUDIENCE', 'ADMIN']))).toBe(true);
    expect(isAdmin(u(['AUDIENCE', 'THEATRE_OWNER']))).toBe(false);
    expect(isAdmin(null)).toBe(false);
  });
});

describe('safeNext', () => {
  it('only follows same-site paths', () => {
    expect(safeNext('/shows/3')).toBe('/shows/3');
    expect(safeNext('//evil.example')).toBe('/');
    expect(safeNext('https://evil.example')).toBe('/');
    expect(safeNext(null)).toBe('/');
  });
});

describe('generated art', () => {
  it('is deterministic per title and wraps words', () => {
    expect(posterSpec('Neon Delta')).toEqual(posterSpec('Neon Delta'));
    expect(titleLines('Little Tigers of Lalbagh')).toEqual(['Little', 'Tigers of', 'Lalbagh']);
    expect(usablePosterUrl('/img/posters/x.jpg')).toBeNull();
    expect(usablePosterUrl('https://img.example/x.jpg')).toBe('https://img.example/x.jpg');
    const m = codeMatrix('BK12345678');
    expect(m).toHaveLength(25);
    expect(m[0]!.slice(0, 7).every(Boolean)).toBe(true);
    expect(codeMatrix('BK12345678')).toEqual(m);
    expect(codeMatrix('BK00000000')).not.toEqual(m);
  });
});

describe('checkLayout', () => {
  it('accepts a valid layout and computes capacity', () => {
    const r = checkLayout(
      JSON.stringify({
        categories: [{ code: 'CLASSIC', name: 'Classic' }],
        rows: [{ row: 'A', seats: 10, category: 'CLASSIC', aisleAfter: [5], blocked: [1, 2] }],
      }),
    );
    expect(r.problems).toEqual([]);
    expect(r.capacity).toBe(8);
  });
  it('reports the domain rules', () => {
    const r = checkLayout(
      JSON.stringify({
        categories: [
          { code: 'CLASSIC', name: 'Classic' },
          { code: 'VIP', name: 'Unused' },
        ],
        rows: [{ row: 'B', seats: 60, category: 'NOPE', aisleAfter: [], blocked: [3, 3] }],
      }),
    );
    expect(r.problems.join('\n')).toMatch(/should be "A"/);
    expect(r.problems.join('\n')).toMatch(/1 to 50/);
    expect(r.problems.join('\n')).toMatch(/NOPE is not defined/);
    expect(r.problems.join('\n')).toMatch(/listed twice/);
    expect(r.problems.join('\n')).toMatch(/not used/);
    expect(checkLayout('{').problems[0]).toMatch(/Not valid JSON/);
  });
});
