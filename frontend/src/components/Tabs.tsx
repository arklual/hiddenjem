/**
 * Вкладки на основе shadcn/ui — с собственной клавиатурной обработкой.
 *
 * Radix здесь намеренно не используется: вкладки уже реализованы по WAI-ARIA с блуждающим
 * tabindex, а поведение «стрелка сразу переключает» выбрано осознанно — все панели уже загружены,
 * и откладывать переключение до Enter значило бы заставлять нажимать лишнее. Менялось оформление;
 * переписывать работающую доступность ради единообразия слоёв незачем.
 */
import { useCallback, useId, useRef, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface TabItem<TValue extends string> {
  value: TValue;
  label: ReactNode;
  /** Optional count chip, e.g. number of rows in the panel. */
  count?: number;
}

export interface TabsProps<TValue extends string> {
  items: readonly TabItem<TValue>[];
  value: TValue;
  onChange: (value: TValue) => void;
  /** Names the tablist for assistive tech. */
  label: string;
  children: ReactNode;
}

export function Tabs<TValue extends string>({
  items,
  value,
  onChange,
  label,
  children,
}: TabsProps<TValue>): React.ReactElement {
  const baseId = useId();
  const listRef = useRef<HTMLDivElement>(null);

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLButtonElement>) => {
      const currentIndex = items.findIndex((item) => item.value === value);
      if (currentIndex === -1) return;

      let nextIndex: number | null = null;
      if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % items.length;
      else if (event.key === 'ArrowLeft')
        nextIndex = (currentIndex - 1 + items.length) % items.length;
      else if (event.key === 'Home') nextIndex = 0;
      else if (event.key === 'End') nextIndex = items.length - 1;
      if (nextIndex === null) return;

      event.preventDefault();
      const next = items[nextIndex];
      if (!next) return;
      onChange(next.value);
      // Move focus with selection so the roving tabindex stays consistent.
      const buttons = listRef.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
      buttons?.[nextIndex]?.focus();
    },
    [items, value, onChange],
  );

  return (
    <div>
      <div
        ref={listRef}
        className="inline-flex w-fit items-center justify-center gap-1 rounded-lg bg-muted p-1 text-muted-foreground"
        role="tablist"
        aria-label={label}
      >
        {items.map((item) => {
          const selected = item.value === value;
          return (
            <button
              key={item.value}
              type="button"
              role="tab"
              id={`${baseId}-tab-${item.value}`}
              aria-selected={selected}
              aria-controls={`${baseId}-panel-${item.value}`}
              tabIndex={selected ? 0 : -1}
              // Arrow-key navigation belongs on the focusable tab, not on the tablist
              // container: the container is never focused, so a handler there only ever
              // fires through bubbling and confuses assistive technology.
              onKeyDown={handleKeyDown}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium',
                'transition-colors outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
                selected
                  ? 'bg-background text-foreground shadow-sm'
                  : 'hover:text-foreground',
              )}
              onClick={() => onChange(item.value)}
            >
              {item.label}
              {item.count !== undefined ? (
                <span className="rounded bg-muted-foreground/15 px-1.5 py-0.5 text-xs tabular-nums">
                  {item.count}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      <div
        role="tabpanel"
        id={`${baseId}-panel-${value}`}
        aria-labelledby={`${baseId}-tab-${value}`}
        tabIndex={0}
        className="mt-4 outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:rounded-md"
      >
        {children}
      </div>
    </div>
  );
}
