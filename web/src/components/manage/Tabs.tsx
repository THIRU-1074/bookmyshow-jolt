import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';

export interface TabDef {
  key: string;
  label: string;
  render: () => ReactNode;
}

/** ARIA tabs whose selection lives in ?tab= so a reload or shared link keeps the place. */
export function Tabs({ tabs, label }: { tabs: TabDef[]; label: string }) {
  const [params, setParams] = useSearchParams();
  const active = tabs.find((t) => t.key === params.get('tab')) ?? (tabs[0] as TabDef);
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});

  const select = (key: string) => {
    const p = new URLSearchParams(params);
    p.set('tab', key);
    setParams(p, { replace: true });
  };

  const onKey = (e: KeyboardEvent) => {
    const i = tabs.findIndex((t) => t.key === active.key);
    const n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : null;
    if (n === null) return;
    e.preventDefault();
    const t = tabs[(n + tabs.length) % tabs.length] as TabDef;
    select(t.key);
    refs.current[t.key]?.focus();
  };

  return (
    <div className="tabs">
      <div role="tablist" aria-label={label} className="tabs__list" onKeyDown={onKey}>
        {tabs.map((t) => (
          <button
            key={t.key}
            ref={(el) => {
              refs.current[t.key] = el;
            }}
            type="button"
            role="tab"
            id={`tab-${t.key}`}
            aria-selected={t.key === active.key}
            aria-controls={`panel-${t.key}`}
            tabIndex={t.key === active.key ? 0 : -1}
            onClick={() => select(t.key)}
            className="tabs__tab"
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${active.key}`} aria-labelledby={`tab-${active.key}`} className="tabs__panel" tabIndex={0}>
        {active.render()}
      </div>
    </div>
  );
}
