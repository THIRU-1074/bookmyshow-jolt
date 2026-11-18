import { useCallback, useEffect, useState } from 'react';
import { storage } from '../lib/storage';

export type Theme = 'dark' | 'light';
const KEY = 'bms.theme';

function systemTheme(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

/**
 * The theme follows prefers-color-scheme until the visitor picks one with the toggle; the
 * choice is stored and applied as data-theme on <html>.
 */
export function useTheme(): [Theme, () => void] {
  const [chosen, setChosen] = useState<Theme | null>(() => {
    const v = storage.get(KEY);
    return v === 'dark' || v === 'light' ? v : null;
  });
  const [system, setSystem] = useState<Theme>(systemTheme);

  useEffect(() => {
    const mq = window.matchMedia?.('(prefers-color-scheme: light)');
    if (!mq) return;
    const on = () => setSystem(mq.matches ? 'light' : 'dark');
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);

  const theme = chosen ?? system;
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#17111f' : '#f3f1f7');
  }, [theme]);

  const toggle = useCallback(() => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    storage.set(KEY, next);
    setChosen(next);
  }, [theme]);

  return [theme, toggle];
}
