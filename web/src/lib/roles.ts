// Role helpers for navigation and route guards. Roles come from GET /api/me: AUDIENCE (every
// account), THEATRE_OWNER and ADMIN. Tokens minted before the USER to AUDIENCE rename still say
// USER, so it counts as AUDIENCE. The UI only hides controls; the server is the authority.
import type { User } from '../api/types';

export function isAudience(u: User | null): boolean {
  return Boolean(u?.roles.some((r) => r === 'USER' || r === 'AUDIENCE'));
}

export function isAdmin(u: User | null): boolean {
  return Boolean(u?.roles.includes('ADMIN'));
}

/** Owners manage their linked cinemas; an admin may use the same dashboard for every cinema. */
export function canUseOwnerDashboard(u: User | null): boolean {
  return Boolean(u?.roles.includes('THEATRE_OWNER') || isAdmin(u));
}
