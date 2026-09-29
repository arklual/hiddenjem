/**
 * Уведомления в облике Hiddenjem: чёрная плашка снизу по центру, до трёх одновременно.
 *
 * Область — вежливая живая область: объявление не перебивает то, что уже читает экранный диктор.
 * Критическое уведомление не исчезает по таймеру — то, на что нужно ответить, не должно пропадать
 * само. Уведомление с действием живёт дольше: до кнопки надо успеть дотянуться. Наведение и фокус
 * останавливают таймер по той же причине.
 */
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { HjIcon } from '@/ui/HjIcon';
import { ToastContext, type Toast, type ToastOptions } from './ToastContext';

const DEFAULT_DURATION_MS = 5000;
const ACTION_DURATION_MS = 8000;
const RESUME_AFTER_MS = 3000;
const MAX_VISIBLE = 3;

export function ToastProvider({ children }: { children: ReactNode }): React.ReactElement {
  const [toasts, setToasts] = useState<readonly Toast[]>([]);
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const counter = useRef(0);

  const clearTimer = useCallback((id: string) => {
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const dismiss = useCallback(
    (id: string) => {
      clearTimer(id);
      setToasts((current) => current.filter((toast) => toast.id !== id));
    },
    [clearTimer],
  );

  const arm = useCallback(
    (id: string, ms: number) => {
      clearTimer(id);
      if (ms > 0)
        timers.current.set(
          id,
          setTimeout(() => dismiss(id), ms),
        );
    },
    [clearTimer, dismiss],
  );

  const show = useCallback(
    (title: string, options: ToastOptions = {}): string => {
      counter.current += 1;
      const id = `toast-${counter.current}`;
      const tone = options.tone ?? 'info';
      const durationMs =
        options.durationMs ??
        (tone === 'critical' ? 0 : options.action ? ACTION_DURATION_MS : DEFAULT_DURATION_MS);
      const toast: Toast = {
        id,
        title,
        tone,
        durationMs,
        ...(options.description !== undefined ? { description: options.description } : {}),
        ...(options.action ? { action: options.action } : {}),
      };
      setToasts((current) => [...current, toast].slice(-MAX_VISIBLE));
      arm(id, durationMs);
      return id;
    },
    [arm],
  );

  const value = useMemo(() => ({ toasts, show, dismiss }), [toasts, show, dismiss]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      {createPortal(
        <div className="toast-region" role="status" aria-live="polite">
          {toasts.map((toast) => {
            const pause = (): void => clearTimer(toast.id);
            const resume = (): void => {
              if (toast.durationMs > 0) arm(toast.id, RESUME_AFTER_MS);
            };
            return (
              <div
                key={toast.id}
                className="toast"
                data-tone={toast.tone}
                onMouseEnter={pause}
                onMouseLeave={resume}
                onFocus={pause}
                onBlur={resume}
              >
                {toast.tone === 'critical' || toast.tone === 'warning' ? (
                  <HjIcon name="alert" size={18} />
                ) : null}
                <p>
                  {toast.title}
                  {toast.description ? (
                    <span className="toast__desc">{toast.description}</span>
                  ) : null}
                </p>
                {toast.action ? (
                  <button
                    type="button"
                    className="btn"
                    onClick={() => {
                      dismiss(toast.id);
                      toast.action?.onClick();
                    }}
                  >
                    {toast.action.label}
                  </button>
                ) : null}
                <button
                  type="button"
                  className="icon-btn"
                  aria-label="Закрыть уведомление"
                  onClick={() => dismiss(toast.id)}
                >
                  <HjIcon name="x" size={18} />
                </button>
              </div>
            );
          })}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  );
}
