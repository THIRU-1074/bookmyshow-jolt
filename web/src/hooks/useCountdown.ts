import { useEffect, useState } from 'react';

/** Seconds left until `expiresAt` (ISO), ticking every second; null when there is no deadline. */
export function useCountdown(expiresAt: string | null | undefined): number | null {
  const deadline = expiresAt ? new Date(expiresAt).getTime() : null;
  const compute = () => (deadline === null ? null : Math.max(0, Math.round((deadline - Date.now()) / 1000)));
  const [left, setLeft] = useState(compute);

  useEffect(() => {
    setLeft(compute());
    if (deadline === null) return;
    const t = window.setInterval(() => {
      const s = compute();
      setLeft(s);
      if (s === 0) window.clearInterval(t);
    }, 1000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deadline]);

  return left;
}
