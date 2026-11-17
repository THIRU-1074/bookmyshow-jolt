// JSON shapes from docs/SPEC.md section 7. Money is integer paise; instants are ISO-8601 UTC.

export type Role = 'USER' | 'AUDIENCE' | 'ADMIN' | 'THEATRE_OWNER' | (string & {});

export interface User {
  id: number;
  email: string;
  name: string;
  roles: Role[];
}

export interface AuthResponse {
  user: User;
  accessToken: string;
  expiresIn: number;
}

export interface TokenResponse {
  accessToken: string;
  expiresIn: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface City {
  id: string;
  name: string;
  timeZone: string;
}

export type Certificate = 'U' | 'UA' | 'A' | 'S';
export type Format = '2D' | '3D' | 'IMAX';

export interface MovieSummary {
  id: number;
  title: string;
  languages: string[];
  genres: string[];
  certificate: Certificate;
  durationMinutes: number;
  releaseDate: string;
  posterUrl: string | null;
}

export interface MovieDetail extends MovieSummary {
  description: string | null;
}

export type Availability = 'AVAILABLE' | 'FILLING_FAST' | 'SOLD_OUT';

export interface ShowSlot {
  id: number;
  startsAt: string;
  localTime: string;
  language: string;
  format: Format;
  minPrice: number;
  availability: Availability;
}

export interface CinemaShows {
  id: number;
  name: string;
  address: string;
  shows: ShowSlot[];
}

export interface ShowTimes {
  date: string;
  cinemas: CinemaShows[];
}

export interface Cinema {
  id: number;
  cityId: string;
  name: string;
  address: string;
}

export interface CinemaDetail extends Cinema {
  screens: { id: number; name: string; capacity: number }[];
}

export interface Price {
  category: string;
  name: string;
  amount: number;
}

export interface ShowDetail {
  id: number;
  movie: { id: number; title: string; certificate: Certificate; durationMinutes: number };
  cinema: { id: number; name: string };
  screen: { id: number; name: string };
  startsAt: string;
  localTime: string;
  language: string;
  format: Format;
  prices: Price[];
  status: 'SCHEDULED' | 'CANCELLED';
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED' | 'BLOCKED';

export interface Seat {
  id: string;
  number: number;
  category: string;
  status: SeatStatus;
}

export interface SeatRow {
  row: string;
  seats: Seat[];
  aisleAfter: number[];
}

export interface SeatMap {
  showId: number;
  version: string;
  categories: { code: string; name: string; amount: number }[];
  rows: SeatRow[];
  holdTtlSeconds: number;
}

export interface Hold {
  id: string;
  showId: number;
  seats: string[];
  amount: number;
  currency: string;
  expiresAt: string;
}

export type BookingStatus = 'HELD' | 'PAYMENT_PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED' | 'REFUNDED';

export interface Booking {
  id: string;
  code: string;
  status: BookingStatus;
  show: { id: number; movieTitle: string; cinemaName: string; screenName: string; startsAt: string; localTime: string };
  seats: { id: string; category: string; price: number }[];
  amount: number;
  currency: string;
  refundAmount: number;
  expiresAt: string;
  version: number;
  createdAt: string;
}

export interface Checkout {
  paymentId: string;
  checkoutUrl: string;
  amount: number;
  currency: string;
  expiresAt: string;
}

export interface LayoutCategory {
  code: string;
  name: string;
}

export interface LayoutRow {
  row: string;
  seats: number;
  category: string;
  aisleAfter: number[];
  blocked: number[];
}

export interface Layout {
  categories: LayoutCategory[];
  rows: LayoutRow[];
}

export interface ShowReport {
  status?: "SCHEDULED" | "CANCELLED";
  showId: number;
  movieTitle: string;
  cinemaName: string;
  startsAt: string;
  capacity: number;
  booked: number;
  occupancyPercent: number;
  revenue: number;
  refunded: number;
}

export interface ShowReportDetail extends ShowReport {
  byCategory: { category: string; capacity: number; booked: number; revenue: number }[];
}

export interface Stats {
  activeHolds: number;
  bookingsConfirmedToday: number;
  sseStreams: number;
}

/** RFC 9457 problem body plus the app's extensions (SPEC section 7, "Errors"). */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  requestId?: string;
  conflicts?: string[];
  errors?: Record<string, string>;
}

export interface AdminUser {
  id: number;
  email: string;
  name: string;
  roles: Role[];
  cinemaIds: number[];
}

export interface Screen {
  id: number;
  cinemaId: number;
  name: string;
  capacity: number;
  layout: Layout;
}

export interface OwnerCinema extends Cinema {
  screens: Screen[];
}

export interface CancelShowResult {
  showId: number;
  refunded: number;
  cancelled: number;
  holdsReleased: number;
}

export interface ShowRequest {
  movieId: number;
  screenId: number;
  startsAt: string;
  language: string;
  format: Format;
  prices: Record<string, number>;
}
