import { useId, useMemo } from 'react';
import type { Layout } from '../../api/types';

export const SAMPLE_LAYOUT: Layout = {
  categories: [
    { code: 'CLASSIC', name: 'Classic' },
    { code: 'PRIME', name: 'Prime' },
  ],
  rows: [
    { row: 'A', seats: 12, category: 'CLASSIC', aisleAfter: [4, 8], blocked: [1, 12] },
    { row: 'B', seats: 12, category: 'CLASSIC', aisleAfter: [4, 8], blocked: [] },
    { row: 'C', seats: 12, category: 'CLASSIC', aisleAfter: [4, 8], blocked: [] },
    { row: 'D', seats: 14, category: 'PRIME', aisleAfter: [4, 10], blocked: [] },
    { row: 'E', seats: 14, category: 'PRIME', aisleAfter: [4, 10], blocked: [] },
  ],
};

export interface LayoutCheck {
  layout: Layout | null;
  problems: string[];
  capacity: number;
}

/**
 * Parses the editor text and runs the same rules as the domain (SPEC section 3) so mistakes show
 * while typing. The server still validates and its field errors are shown on submit.
 */
export function checkLayout(text: string): LayoutCheck {
  let doc: unknown;
  try {
    doc = JSON.parse(text);
  } catch (e) {
    return { layout: null, problems: [`Not valid JSON: ${(e as Error).message}`], capacity: 0 };
  }
  const l = doc as Layout;
  const problems: string[] = [];
  if (!l || !Array.isArray(l.categories) || !Array.isArray(l.rows)) {
    return { layout: null, problems: ['Needs "categories" and "rows" arrays.'], capacity: 0 };
  }
  const codes = new Set<string>();
  for (const c of l.categories) {
    if (!/^[A-Z_]{2,16}$/.test(c?.code ?? '')) problems.push(`Category code "${c?.code}" must be 2 to 16 capital letters.`);
    if (codes.has(c.code)) problems.push(`Category ${c.code} is listed twice.`);
    codes.add(c.code);
  }
  if (l.rows.length < 1 || l.rows.length > 26) problems.push('Use 1 to 26 rows.');
  let capacity = 0;
  const used = new Set<string>();
  l.rows.forEach((r, i) => {
    const want = String.fromCharCode(65 + i);
    if (r?.row !== want) problems.push(`Row ${i + 1} should be "${want}": rows run from A without gaps.`);
    if (!Number.isInteger(r?.seats) || r.seats < 1 || r.seats > 50) problems.push(`Row ${r?.row}: seats must be 1 to 50.`);
    if (!codes.has(r?.category)) problems.push(`Row ${r?.row}: category ${r?.category} is not defined.`);
    used.add(r?.category);
    const blocked = r?.blocked ?? [];
    if (new Set(blocked).size !== blocked.length) problems.push(`Row ${r?.row}: a blocked seat is listed twice.`);
    for (const n of [...blocked, ...(r?.aisleAfter ?? [])]) {
      if (n < 1 || n > r.seats) problems.push(`Row ${r?.row}: seat ${n} is outside 1 to ${r.seats}.`);
    }
    capacity += Math.max(0, (r?.seats ?? 0) - new Set(blocked).size);
  });
  for (const c of codes) if (!used.has(c)) problems.push(`Category ${c} is not used by any row.`);
  if (capacity < 1) problems.push('At least one seat must be bookable.');
  return { layout: l, problems, capacity };
}

export function LayoutEditor({ value, onChange, serverErrors }: { value: string; onChange: (v: string) => void; serverErrors?: Record<string, string> }) {
  const id = useId();
  const check = useMemo(() => checkLayout(value), [value]);
  const server = Object.entries(serverErrors ?? {}).filter(([k]) => k.startsWith('layout') || k.startsWith('rows') || k.startsWith('categories'));
  return (
    <div className="layout-editor">
      <div className="field">
        <label htmlFor={`${id}-json`}>Seat layout (JSON)</label>
        <textarea
          id={`${id}-json`}
          className="code-input"
          spellCheck={false}
          rows={14}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={check.problems.length ? true : undefined}
          aria-describedby={`${id}-status`}
        />
        <div id={`${id}-status`} className="layout-editor__status" aria-live="polite">
          {check.problems.length ? (
            <ul className="field-error">
              {check.problems.slice(0, 6).map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          ) : (
            <p className="field__hint">Looks valid. {check.capacity} bookable seats.</p>
          )}
          {!!server.length && (
            <ul className="field-error">
              {server.map(([k, v]) => (
                <li key={k}>
                  <code>{k}</code>: {v}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
      <LayoutPreview layout={check.layout} />
    </div>
  );
}

export function LayoutPreview({ layout }: { layout: Layout | null }) {
  if (!layout || !Array.isArray(layout.rows)) {
    return <div className="layout-preview layout-preview--empty">The preview appears once the JSON parses.</div>;
  }
  const order = (layout.categories ?? []).map((c) => c.code);
  return (
    <figure className="layout-preview" aria-label="Layout preview">
      <div className="layout-preview__screen" aria-hidden="true" />
      <div className="layout-preview__rows">
        {layout.rows.map((r, i) => {
          const blocked = new Set(r?.blocked ?? []);
          const aisles = new Set(r?.aisleAfter ?? []);
          const tier = Math.max(0, order.indexOf(r?.category));
          const n = Math.min(50, Math.max(0, Number(r?.seats) || 0));
          return (
            <div key={`${r?.row}-${i}`} className={`mini-row seatrow--tier${tier % 4}`}>
              <span className="mini-row__label">{r?.row}</span>
              {Array.from({ length: n }, (_, k) => (
                <span key={k} className="mini-row__cell">
                  <span className={`mini-seat${blocked.has(k + 1) ? ' is-blocked' : ''}`} />
                  {aisles.has(k + 1) && <span className="mini-gap" />}
                </span>
              ))}
            </div>
          );
        })}
      </div>
      <figcaption>
        {(layout.categories ?? []).map((c, i) => (
          <span key={c.code} className="legend-chip">
            <span className={`tier-swatch tier-swatch--${i % 4}`} aria-hidden="true" />
            {c.name}
          </span>
        ))}
        <span className="legend-chip">
          <span className="mini-seat is-blocked" aria-hidden="true" /> Blocked
        </span>
      </figcaption>
    </figure>
  );
}
