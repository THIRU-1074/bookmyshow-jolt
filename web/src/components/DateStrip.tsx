import { useRef, type KeyboardEvent } from 'react';
import { addDays, dateParts } from '../lib/format';

/** Seven days from `today` as a single-select radio group; arrow keys move between days. */
export function DateStrip({ today, value, onChange, days = 7 }: { today: string; value: string; onChange: (d: string) => void; days?: number }) {
  const dates = Array.from({ length: days }, (_, i) => addDays(today, i));
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  const onKey = (e: KeyboardEvent, i: number) => {
    const next = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
    if (next === null || next < 0 || next >= dates.length) return;
    e.preventDefault();
    onChange(dates[next] as string);
    refs.current[next]?.focus();
  };

  return (
    <div className="datestrip" role="radiogroup" aria-label="Show date">
      {dates.map((d, i) => {
        const p = dateParts(d);
        const on = d === value;
        return (
          <button
            key={d}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            role="radio"
            aria-checked={on}
            aria-label={i === 0 ? `Today, ${p.long}` : i === 1 ? `Tomorrow, ${p.long}` : p.long}
            tabIndex={on ? 0 : -1}
            className="datestrip__day"
            onClick={() => onChange(d)}
            onKeyDown={(e) => onKey(e, i)}
          >
            <span className="datestrip__wd" aria-hidden="true">
              {i === 0 ? 'Today' : p.weekday}
            </span>
            <span className="datestrip__d" aria-hidden="true">
              {p.day}
            </span>
            <span className="datestrip__m" aria-hidden="true">
              {p.month}
            </span>
          </button>
        );
      })}
    </div>
  );
}
