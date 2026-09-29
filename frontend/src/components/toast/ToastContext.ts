import { createContext, useContext } from 'react';

export type ToastTone = 'info' | 'good' | 'warning' | 'critical';

/** Одно действие прямо в уведомлении: «Открыть отчёт», «Отменить пометку». */
export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastOptions {
  tone?: ToastTone;
  description?: string;
  /** Milliseconds before auto-dismiss; `0` keeps it until dismissed. */
  durationMs?: number;
  action?: ToastAction;
}

export interface Toast extends Required<Pick<ToastOptions, 'tone'>> {
  id: string;
  title: string;
  description?: string;
  durationMs: number;
  action?: ToastAction;
}

export interface ToastContextValue {
  toasts: readonly Toast[];
  show: (title: string, options?: ToastOptions) => string;
  dismiss: (id: string) => void;
}

export const ToastContext = createContext<ToastContextValue | null>(null);

export function useToast(): ToastContextValue {
  const value = useContext(ToastContext);
  if (!value) {
    throw new Error('useToast должен вызываться внутри <ToastProvider>');
  }
  return value;
}
