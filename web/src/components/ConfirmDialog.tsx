import { useEffect, useRef, type ReactNode } from 'react';

interface Props {
  title: string;
  children?: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}

/** A modal confirmation built on <dialog>, which traps focus and closes on Escape natively. */
export function ConfirmDialog({ title, children, confirmLabel, cancelLabel = 'Go back', danger, onConfirm, onClose }: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal?.();
  }, []);
  return (
    <dialog ref={ref} className="sheet sheet--confirm" aria-labelledby="confirm-title" onClose={onClose}>
      <div className="sheet__body">
        <h2 id="confirm-title">{title}</h2>
        {children}
        <div className="sheet__actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} autoFocus>
            {cancelLabel}
          </button>
          <button type="button" className={`btn${danger ? ' btn--danger' : ''}`} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </dialog>
  );
}
