import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';

export type ToastKind = 'info' | 'success' | 'error';

interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
  detail?: string;
}

interface ToastApi {
  show: (message: string, kind?: ToastKind, detail?: string) => void;
  error: (e: unknown) => void;
}

const ToastContext = createContext<ToastApi>({ show: () => {}, error: () => {} });

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seq = useRef(0);

  const dismiss = useCallback((id: number) => setToasts((t) => t.filter((x) => x.id !== id)), []);

  const show = useCallback(
    (message: string, kind: ToastKind = 'info', detail?: string) => {
      const id = ++seq.current;
      setToasts((t) => [...t.slice(-2), { id, kind, message, detail }]);
      window.setTimeout(() => dismiss(id), kind === 'error' ? 8000 : 5000);
    },
    [dismiss],
  );

  const error = useCallback(
    (e: unknown) => {
      if (e instanceof ApiError) show(e.message, 'error', e.requestId ? `Reference ${e.requestId}` : undefined);
      else show(e instanceof Error ? e.message : 'Something went wrong.', 'error');
    },
    [show],
  );

  const value = useMemo(() => ({ show, error }), [show, error]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast--${t.kind}`}>
            <span className="toast__icon" aria-hidden="true">
              {t.kind === 'error' ? '!' : t.kind === 'success' ? '✓' : 'i'}
            </span>
            <div>
              <p className="toast__msg">{t.message}</p>
              {t.detail && <p className="toast__detail">{t.detail}</p>}
            </div>
            <button type="button" className="toast__close" onClick={() => dismiss(t.id)} aria-label="Dismiss message">
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  return useContext(ToastContext);
}
