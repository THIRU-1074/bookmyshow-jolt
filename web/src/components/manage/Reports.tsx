import { useState } from 'react';
import { api } from '../../api/client';
import type { City, Page, ShowReport, ShowReportDetail } from '../../api/types';
import { useAsync } from '../../hooks/useAsync';
import { addDays, DEFAULT_ZONE, formatDateTime, money, todayIn } from '../../lib/format';
import { ErrorState, EmptyState, LoadingBlock, Skeleton } from '../States';
import { Field } from './FormBits';

/**
 * Sales and occupancy for a date range: headline tiles, revenue by day, occupancy by show and the
 * full table. Charts are plain SVG with one series each, so the accent colour carries no meaning
 * beyond "this measure"; values are always printed as text too.
 */
const MAX_PAGES = 10;

export function Reports({ prefix, cities }: { prefix: '/api/admin' | '/api/owner'; cities?: City[] }) {
  const today = todayIn(DEFAULT_ZONE);
  const [from, setFrom] = useState(addDays(today, -7));
  const [to, setTo] = useState(addDays(today, 7));
  const [city, setCity] = useState('');
  const [detailId, setDetailId] = useState<number | null>(null);
  const [shown, setShown] = useState(25);
  const { data, error, loading, reload } = useAsync(
    async (signal) => {
      // Totals and charts need every show in the range, so walk the pages (100 is the API's cap).
      const q = { from, to, city: city || undefined, size: 100 };
      const first = await api.get<Page<ShowReport>>(`${prefix}/reports/shows`, { ...q, page: 1 }, signal);
      const rest = await Promise.all(
        Array.from({ length: Math.min(first.totalPages, MAX_PAGES) - 1 }, (_, i) =>
          api.get<Page<ShowReport>>(`${prefix}/reports/shows`, { ...q, page: i + 2 }, signal),
        ),
      );
      setShown(25);
      return { items: [first, ...rest].flatMap((p) => p.items), total: first.total };
    },
    [prefix, from, to, city],
  );

  const rows = data?.items ?? [];
  const live = rows.filter((r) => r.status !== 'CANCELLED');
  const revenue = rows.reduce((s, r) => s + r.revenue, 0);
  const refunded = rows.reduce((s, r) => s + r.refunded, 0);
  const booked = live.reduce((s, r) => s + r.booked, 0);
  const capacity = live.reduce((s, r) => s + r.capacity, 0);
  const pct = capacity ? (booked / capacity) * 100 : 0;
  const occupancy = pct > 0 && pct < 0.1 ? '<0.1' : pct > 0 && pct < 10 ? pct.toFixed(1) : String(Math.round(pct));
  // The busiest shows say more than the first ones in time order.
  const busiest = [...live].sort((a, b) => b.occupancyPercent - a.occupancyPercent || b.booked - a.booked).slice(0, 12);

  return (
    <div className="reports">
      <div className="form-row">
        <Field id="r-from" label="From">
          <input id="r-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field id="r-to" label="To">
          <input id="r-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </Field>
        {cities && (
          <Field id="r-city" label="City">
            <select id="r-city" value={city} onChange={(e) => setCity(e.target.value)}>
              <option value="">All cities</option>
              {cities.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
        )}
      </div>
      {loading && (
        <LoadingBlock label="Loading report">
          <div className="tiles">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="skeleton--tile" />
            ))}
          </div>
          <Skeleton className="skeleton--chart" />
        </LoadingBlock>
      )}
      {error && <ErrorState error={error} onRetry={reload} />}
      {data && !loading && !rows.length && (
        <EmptyState title="No shows in this range" art="reel">
          <p>Widen the dates or schedule a show.</p>
        </EmptyState>
      )}
      {data && !loading && !!rows.length && (
        <>
          <dl className="tiles">
            <div className="tile">
              <dt>Net revenue</dt>
              <dd>{money(revenue)}</dd>
            </div>
            <div className="tile">
              <dt>Tickets sold</dt>
              <dd>{booked.toLocaleString('en-IN')}</dd>
            </div>
            <div className="tile">
              <dt>Occupancy</dt>
              <dd>{occupancy}%</dd>
            </div>
            <div className="tile">
              <dt>Refunded</dt>
              <dd>{money(refunded)}</dd>
            </div>
          </dl>
          <div className="charts">
            <RevenueByDay rows={rows} />
            <OccupancyBars rows={busiest} onPick={setDetailId} />
          </div>
          <div className="table-scroll">
            <table className="table">
              <caption className="visually-hidden">Shows in range</caption>
              <thead>
                <tr>
                  <th scope="col">Film</th>
                  <th scope="col">Cinema</th>
                  <th scope="col">Starts</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="num">
                    Sold
                  </th>
                  <th scope="col" className="num">
                    Occupancy
                  </th>
                  <th scope="col" className="num">
                    Revenue
                  </th>
                  <th scope="col" className="num">
                    Refunded
                  </th>
                  <th scope="col">
                    <span className="visually-hidden">Details</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.slice(0, shown).map((r) => (
                  <tr key={r.showId}>
                    <td>{r.movieTitle}</td>
                    <td>{r.cinemaName}</td>
                    <td>{formatDateTime(r.startsAt)}</td>
                    <td>{r.status === 'CANCELLED' ? '✕ Cancelled' : 'Scheduled'}</td>
                    <td className="num">
                      {r.booked}/{r.capacity}
                    </td>
                    <td className="num">{r.occupancyPercent}%</td>
                    <td className="num">{money(r.revenue)}</td>
                    <td className="num">{money(r.refunded)}</td>
                    <td>
                      <button type="button" className="link-btn" onClick={() => setDetailId(r.showId)}>
                        By category<span className="visually-hidden"> for show {r.showId}</span>
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.total > shown && (
            <p className="table-more">
              Showing {Math.min(shown, rows.length)} of {data.total} shows.{' '}
              {shown < rows.length && (
                <button type="button" className="link-btn" onClick={() => setShown((n) => n + 50)}>
                  Show 50 more
                </button>
              )}
              {rows.length < data.total && ` Totals cover the first ${rows.length}; narrow the dates for the rest.`}
            </p>
          )}
          {detailId !== null && <ReportDetail prefix={prefix} id={detailId} onClose={() => setDetailId(null)} />}
        </>
      )}
    </div>
  );
}

function RevenueByDay({ rows }: { rows: ShowReport[] }) {
  const byDay = new Map<string, number>();
  for (const r of rows) {
    const d = todayIn(DEFAULT_ZONE, new Date(r.startsAt));
    byDay.set(d, (byDay.get(d) ?? 0) + r.revenue);
  }
  const days = [...byDay.entries()].sort(([a], [b]) => a.localeCompare(b));
  const max = Math.max(1, ...days.map(([, v]) => v));
  const W = 560;
  const H = 200;
  const pad = { l: 8, r: 8, t: 24, b: 28 };
  const bw = Math.max(6, Math.min(40, (W - pad.l - pad.r) / days.length - 6));
  const step = (W - pad.l - pad.r) / days.length;
  const best = days.reduce((m, d) => (d[1] > m[1] ? d : m), days[0] ?? ['', 0]);
  return (
    <figure className="chart">
      <figcaption>Net revenue by day</figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Net revenue by day. Highest ${money(best[1])} on ${best[0]}.`}>
        <line x1={pad.l} x2={W - pad.r} y1={H - pad.b} y2={H - pad.b} className="chart__axis" />
        {days.map(([d, v], i) => {
          const h = ((H - pad.t - pad.b) * v) / max;
          const x = pad.l + i * step + (step - bw) / 2;
          const y = H - pad.b - h;
          return (
            <g key={d} className="chart__mark">
              <title>{`${d}: ${money(v)}`}</title>
              <rect x={x - 2} y={pad.t} width={bw + 4} height={H - pad.t - pad.b} className="chart__hit" />
              <path d={barPath(x, y, bw, h)} className="chart__bar" />
              {(days.length <= 8 || d === best[0]) && v > 0 && (
                <text x={x + bw / 2} y={y - 6} textAnchor="middle" className="chart__value">
                  {money(v)}
                </text>
              )}
              {(days.length <= 10 || i % 2 === 0) && (
                <text x={x + bw / 2} y={H - 10} textAnchor="middle" className="chart__tick">
                  {d.slice(8)}/{d.slice(5, 7)}
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </figure>
  );
}

/** Rounded top, square base: data ends are rounded, the baseline stays flat. */
function barPath(x: number, y: number, w: number, h: number): string {
  if (h <= 0) return '';
  const r = Math.min(4, w / 2, h);
  return `M${x} ${y + h}V${y + r}Q${x} ${y} ${x + r} ${y}H${x + w - r}Q${x + w} ${y} ${x + w} ${y + r}V${y + h}Z`;
}

function OccupancyBars({ rows, onPick }: { rows: ShowReport[]; onPick: (id: number) => void }) {
  const rowH = 26;
  const W = 560;
  const labelW = 200;
  const H = rows.length * rowH + 8;
  return (
    <figure className="chart">
      <figcaption>Busiest shows by occupancy</figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Occupancy by show; the table below lists every value.">
        {rows.map((r, i) => {
          const y = 4 + i * rowH;
          const full = W - labelW - 56;
          const w = (full * Math.min(100, r.occupancyPercent)) / 100;
          return (
            <g key={r.showId} className="chart__mark chart__mark--click" onClick={() => onPick(r.showId)}>
              <title>{`${r.movieTitle}, ${formatDateTime(r.startsAt)}: ${r.booked} of ${r.capacity} seats (${r.occupancyPercent}%)`}</title>
              <rect x="0" y={y} width={W} height={rowH - 2} className="chart__hit" />
              <text x="0" y={y + 16} className="chart__label">
                {truncate(r.movieTitle, 14)}, {formatDateTime(r.startsAt).replace(/^\w+, /, '').replace(/ (am|pm)$/, '$1')}
              </text>
              <rect x={labelW} y={y + 6} width={full} height="10" rx="5" className="chart__track" />
              {w > 0 && <rect x={labelW} y={y + 6} width={Math.max(10, w)} height="10" rx="5" className="chart__bar" />}
              <text x={W} y={y + 16} textAnchor="end" className="chart__value">
                {r.occupancyPercent}%
              </text>
            </g>
          );
        })}
      </svg>
    </figure>
  );
}

function truncate(s: string, n: number) {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

function ReportDetail({ prefix, id, onClose }: { prefix: string; id: number; onClose: () => void }) {
  const { data, error, loading } = useAsync((signal) => api.get<ShowReportDetail>(`${prefix}/reports/shows/${id}`, undefined, signal), [prefix, id]);
  return (
    <section className="panel report-detail" aria-label={`Show ${id} by category`}>
      <div className="panel__head">
        <h3>{data ? `${data.movieTitle}, ${formatDateTime(data.startsAt)}` : `Show ${id}`}</h3>
        <button type="button" className="btn btn--sm btn--ghost" onClick={onClose}>
          Close
        </button>
      </div>
      {loading && <Skeleton className="skeleton--line" />}
      {error && <ErrorState error={error} />}
      {data && (
        <ul className="cat-bars" role="list">
          {data.byCategory.map((c) => {
            const pct = c.capacity ? Math.round((c.booked / c.capacity) * 100) : 0;
            return (
              <li key={c.category}>
                <span className="cat-bars__name">{c.category}</span>
                <span className="meter" aria-hidden="true">
                  <span className="meter__fill" data-pct={pct} ref={(el) => el?.style.setProperty('--pct', `${pct}%`)} />
                </span>
                <span className="cat-bars__val">
                  {c.booked}/{c.capacity} seats, {money(c.revenue)}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
