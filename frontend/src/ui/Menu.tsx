/**
 * Выпадающее меню: кнопка и список действий под ней.
 *
 * Клавиатура по образцу WAI-ARIA: открытие переводит фокус на первый пункт, стрелки ходят по
 * кругу, Esc закрывает и возвращает фокус на кнопку, Tab закрывает и уходит дальше. Щелчок мимо
 * меню закрывает его.
 */
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';

export interface MenuProps {
  /** Содержимое кнопки. */
  trigger: ReactNode;
  triggerClassName: string;
  triggerLabel?: string;
  label: string;
  /** Пункты — элементы с `role="menuitem"`; `close` закрывает меню после действия. */
  children: (close: () => void) => ReactNode;
  head?: ReactNode;
}

export function Menu({
  trigger,
  triggerClassName,
  triggerLabel,
  label,
  children,
  head,
}: MenuProps): React.ReactElement {
  const [open, setOpen] = useState(false);
  const id = useId();
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);

  const items = (): HTMLElement[] =>
    Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);

  useEffect(() => {
    if (!open) return;
    items()[0]?.focus();
    const onPointer = (event: MouseEvent): void => {
      if (!wrap.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onPointer);
    return () => document.removeEventListener('mousedown', onPointer);
  }, [open]);

  const close = (returnFocus = false): void => {
    setOpen(false);
    if (returnFocus) button.current?.focus();
  };

  return (
    <div className="menu-wrap" ref={wrap}>
      <button
        ref={button}
        type="button"
        className={triggerClassName}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={id}
        aria-label={triggerLabel}
        onClick={() => setOpen((value) => !value)}
      >
        {trigger}
      </button>
      <div
        ref={menu}
        className="menu"
        id={id}
        role="menu"
        tabIndex={-1}
        aria-label={label}
        hidden={!open}
        onKeyDown={(event) => {
          const list = items();
          const index = list.indexOf(document.activeElement as HTMLElement);
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            list[(index + 1) % list.length]?.focus();
          } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            list[(index - 1 + list.length) % list.length]?.focus();
          } else if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            close(true);
          } else if (event.key === 'Tab') {
            close();
          }
        }}
      >
        {head}
        {open ? children(() => close()) : null}
      </div>
    </div>
  );
}
