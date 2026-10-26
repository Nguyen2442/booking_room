import { Children, cloneElement, isValidElement, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { AlertTriangle, ChevronLeft, ChevronRight, Loader2, X } from 'lucide-react';
import { useI18n } from '../lib/i18n';

export function Spinner({ size = 18 }: { size?: number }) {
  const { t } = useI18n();
  return <Loader2 size={size} className="spin" aria-label={t('common.loading')} />;
}

type BtnProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  loading?: boolean;
  icon?: ReactNode;
};

export function Button({ variant = 'secondary', loading, icon, children, disabled, className = '', ...rest }: BtnProps) {
  return (
    <button
      type="button"
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`btn btn-${variant} ${className}`}
    >
      {loading ? <Spinner size={16} /> : icon}
      {children}
    </button>
  );
}

const CONTROLS = new Set(['input', 'select', 'textarea']);

/** Walks the (small) subtree and wires the first form control to its label and error text. */
function wire(node: ReactNode, id: string, errorId: string | undefined, state: { done: boolean }): ReactNode {
  return Children.map(node, (child) => {
    if (!isValidElement<{ children?: ReactNode; id?: string; type?: string }>(child)) return child;
    if (typeof child.type === 'string' && CONTROLS.has(child.type) && !state.done) {
      state.done = true;
      const extra: Record<string, unknown> = { id: child.props.id ?? id };
      if (errorId) {
        extra['aria-invalid'] = true;
        extra['aria-describedby'] = errorId;
      }
      return cloneElement(child, extra);
    }
    if (child.props.children) return cloneElement(child, undefined, wire(child.props.children, id, errorId, state));
    return child;
  });
}

/**
 * Label is linked with htmlFor (not by wrapping the control): controls may contain buttons - e.g. the
 * show-password toggle - and a wrapping <label> would fold their text into the input's accessible name.
 */
export function Field({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: ReactNode }) {
  const id = useId();
  const errorId = error ? `${id}-err` : undefined;
  return (
    <div className={`field ${error ? 'has-error' : ''}`}>
      <label className="field-label" htmlFor={id}>{label}</label>
      {wire(children, id, errorId, { done: false })}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && <span id={errorId} className="field-error" role="alert">{error}</span>}
    </div>
  );
}

export function Badge({ tone = 'neutral', children }: { tone?: 'neutral' | 'success' | 'danger' | 'warning' | 'accent'; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function Chip({ children }: { children: ReactNode }) {
  return <span className="chip">{children}</span>;
}

export function EmptyState({ icon, title, children }: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {icon && <div className="empty-icon">{icon}</div>}
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t, err } = useI18n();
  return (
    <div className="empty" role="alert">
      <div className="empty-icon danger"><AlertTriangle size={28} /></div>
      <h3>{t('common.couldNotLoad')}</h3>
      <p>{err(error)}</p>
      {onRetry && <Button onClick={onRetry}>{t('common.tryAgain')}</Button>}
    </div>
  );
}

export function Skeleton({ h = 16, w = '100%' }: { h?: number; w?: number | string }) {
  return <div className="skeleton" style={{ height: h, width: w }} aria-hidden />;
}

export function Banner({ tone, children }: { tone: 'danger' | 'warning' | 'info'; children: ReactNode }) {
  return <div className={`banner banner-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>{children}</div>;
}

/** Native <dialog>: focus trapping, Escape to close and inert background come from the browser. */
export function Modal({
  open, onClose, title, children, footer, wide,
}: { open: boolean; onClose: () => void; title: string; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);
  // dialog.close() fires a native "close" event too; that one must not reach onClose, or closing a
  // dialog from code (e.g. swapping to a confirmation dialog) would also tell the parent "user dismissed it".
  const closedByCode = useRef(false);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) {
      closedByCode.current = true;
      d.close();
    }
  }, [open]);
  return (
    <dialog
      ref={ref}
      className={`modal ${wide ? 'modal-wide' : ''}`}
      onClose={() => {
        if (closedByCode.current) closedByCode.current = false;
        else onClose();
      }}
      onMouseDown={(e) => { if (e.target === ref.current) onClose(); }}
      aria-label={title}
    >
      {open && (
        <div className="modal-card">
          <header className="modal-head">
            <h2>{title}</h2>
            <button className="icon-btn" aria-label={t('common.close')} onClick={onClose}><X size={20} /></button>
          </header>
          <div className="modal-body">{children}</div>
          {footer && <footer className="modal-foot">{footer}</footer>}
        </div>
      )}
    </dialog>
  );
}

export function ConfirmDialog({
  open, title, message, confirmLabel, danger, loading, onConfirm, onCancel, children,
}: {
  open: boolean; title: string; message?: ReactNode; confirmLabel?: string; danger?: boolean; loading?: boolean;
  onConfirm: () => void; onCancel: () => void; children?: ReactNode;
}) {
  const { t } = useI18n();
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title={title}
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={loading}>{t('common.keep')}</Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} loading={loading}>{confirmLabel ?? t('common.confirm')}</Button>
        </>
      }
    >
      {message && <p className="muted">{message}</p>}
      {children}
    </Modal>
  );
}

export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  const { t } = useI18n();
  if (totalPages <= 1) return null;
  return (
    <nav className="pagination" aria-label={t('common.pagination')}>
      <Button variant="ghost" icon={<ChevronLeft size={16} />} disabled={page === 0} onClick={() => onChange(page - 1)}>{t('common.previous')}</Button>
      <span className="muted">{t('common.pageOf', { page: page + 1, total: totalPages })}</span>
      <Button variant="ghost" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>
        {t('common.next')} <ChevronRight size={16} />
      </Button>
    </nav>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}
