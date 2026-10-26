import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { CheckCircle2, Info, XCircle, X } from 'lucide-react';
import { useI18n } from './i18n';

type Tone = 'success' | 'error' | 'info';
interface ToastItem { id: number; tone: Tone; text: string }
interface ToastApi { success: (t: string) => void; error: (t: string) => void; info: (t: string) => void }

const Ctx = createContext<ToastApi | null>(null);
let seq = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const { t: tr } = useI18n();
  const [items, setItems] = useState<ToastItem[]>([]);
  const dismiss = (id: number) => setItems((l) => l.filter((x) => x.id !== id));
  const push = useCallback((tone: Tone, text: string) => {
    const id = ++seq;
    setItems((l) => [...l.slice(-3), { id, tone, text }]);
    setTimeout(() => dismiss(id), tone === 'error' ? 7000 : 4000);
  }, []);
  const api = useMemo<ToastApi>(
    () => ({ success: (t) => push('success', t), error: (t) => push('error', t), info: (t) => push('info', t) }),
    [push],
  );
  const Icon = { success: CheckCircle2, error: XCircle, info: Info };
  return (
    <Ctx.Provider value={api}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {items.map((t) => {
          const I = Icon[t.tone];
          return (
            <div key={t.id} className={`toast toast-${t.tone}`}>
              <I size={18} aria-hidden />
              <span>{t.text}</span>
              <button className="icon-btn" aria-label={tr('common.toastDismiss')} onClick={() => dismiss(t.id)}><X size={16} /></button>
            </div>
          );
        })}
      </div>
    </Ctx.Provider>
  );
}

export function useToast() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useToast outside ToastProvider');
  return v;
}
