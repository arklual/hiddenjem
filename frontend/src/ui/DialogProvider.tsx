/**
 * Одно модальное окно на всё приложение — нативный `<dialog>` с `showModal()`.
 *
 * Нативный, а не нарисованный: браузер сам держит фокус внутри, закрывает по Esc и делает остальную
 * страницу инертной. Окно одно, потому что второе поверх первого в этом продукте не нужно ни разу, а
 * общий провайдер позволяет открыть подтверждение из любого места — из строки темы, из уведомления,
 * из меню — без собственной разметки у каждого.
 *
 * Фокус после закрытия возвращается туда, где был до открытия: иначе клавиатурный пользователь
 * оказывается в начале страницы и заново ищет место, где работал.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { HjIcon } from './HjIcon';

export interface DialogAction {
  label: string;
  primary?: boolean;
  autoFocus?: boolean;
  onClick?: () => void;
}

export interface DialogConfig {
  title: ReactNode;
  body: ReactNode;
  actions?: DialogAction[];
  /** Шире обычного — для справки с таблицами. */
  wide?: boolean;
}

interface DialogContextValue {
  open: (config: DialogConfig) => void;
  close: () => void;
}

const DialogContext = createContext<DialogContextValue | null>(null);

export function useDialog(): DialogContextValue {
  const value = useContext(DialogContext);
  if (!value) throw new Error('useDialog должен вызываться внутри <DialogProvider>');
  return value;
}

export function DialogProvider({ children }: { children: ReactNode }): React.ReactElement {
  const ref = useRef<HTMLDialogElement>(null);
  const returnTo = useRef<HTMLElement | null>(null);
  const [config, setConfig] = useState<DialogConfig | null>(null);
  // Действие может открыть следующее окно (подтверждение → записка). Тогда закрывать нельзя:
  // событие `close` придёт позже и погасит уже новое окно.
  const generation = useRef(0);

  const close = useCallback(() => {
    ref.current?.close();
  }, []);

  const open = useCallback((next: DialogConfig) => {
    generation.current += 1;
    if (!ref.current?.open) {
      returnTo.current =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
    }
    setConfig(next);
  }, []);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog || !config) return;
    if (!dialog.open) dialog.showModal();
    const auto = dialog.querySelector<HTMLElement>('[data-autofocus="true"]');
    (auto ?? dialog.querySelector<HTMLElement>('[data-dialog-close]'))?.focus();
  }, [config]);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const onClose = (): void => {
      setConfig(null);
      const target = returnTo.current;
      returnTo.current = null;
      if (target && document.body.contains(target)) target.focus();
    };
    dialog.addEventListener('close', onClose);
    return () => dialog.removeEventListener('close', onClose);
  }, []);

  const value = useMemo(() => ({ open, close }), [open, close]);

  return (
    <DialogContext.Provider value={value}>
      {children}
      {/* Щелчок по подложке дублирует Esc, который нативный диалог обрабатывает сам. */}
      {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions */}
      <dialog
        ref={ref}
        aria-labelledby="dialog-title"
        className={config?.wide ? 'dialog--wide' : undefined}
        onClick={(event) => {
          // Щелчок по подложке — за пределами содержимого — закрывает окно, как Esc.
          if (event.target === ref.current) close();
        }}
      >
        {config ? (
          <div className="dlg">
            <div className="dlg__head">
              <h2 className="h2" id="dialog-title">
                {config.title}
              </h2>
              <button
                type="button"
                className="icon-btn"
                aria-label="Закрыть"
                data-dialog-close
                onClick={close}
              >
                <HjIcon name="x" />
              </button>
            </div>
            <div className="dlg__body">{config.body}</div>
            {config.actions && config.actions.length > 0 ? (
              <div className="dlg__actions">
                {config.actions.map((action) => (
                  <button
                    key={action.label}
                    type="button"
                    className={`btn ${action.primary ? 'btn--primary' : 'btn--secondary'}`}
                    data-autofocus={action.autoFocus ? 'true' : undefined}
                    onClick={() => {
                      const before = generation.current;
                      action.onClick?.();
                      if (generation.current === before) close();
                    }}
                  >
                    {action.label}
                  </button>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}
      </dialog>
    </DialogContext.Provider>
  );
}
