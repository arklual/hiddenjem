import type { ReactNode, TdHTMLAttributes, ThHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';
import { formatCount } from '@/lib/format';
import { useT } from '@/lib/i18n/useT';
import { Button } from './Button';

/**
 * Таблица на основе shadcn/ui.
 *
 * Область прокрутки получает `tabindex=0` и роль `group`: широкую таблицу надо уметь пролистать без
 * мыши, а фокусируемый контейнер прокрутки — принятый способ дать такую возможность (WCAG 2.1.1).
 * `caption` обязателен и не может быть пустым — таблица без названия для читающего с экрана просто
 * решётка чисел.
 */
export function Table({
  caption,
  children,
  className,
}: {
  /** Describes the table for screen readers. Required — never ship an unlabelled table. */
  caption: string;
  children: ReactNode;
  className?: string;
}): React.ReactElement {
  // A horizontally scrollable region must be reachable by keyboard (WCAG 2.1.1), which is
  // exactly what tabIndex={0} provides here; the role and label make the resulting tab stop
  // meaningful rather than mysterious. The lint rule cannot tell this apart from a stray
  // tabindex on decorative markup.
  /* Правило не отличает этот случай от случайного tabindex на украшении, а случай законный:
     прокручиваемая область обязана быть достижима с клавиатуры (WCAG 2.1.1), и роль с подписью
     делают появившуюся остановку осмысленной. Отключение блочное, а не построчное: элемент
     занимает несколько строк, и `disable-next-line` покрывал бы только первую. */
  /* eslint-disable jsx-a11y/no-noninteractive-tabindex */
  return (
    <div
      className="relative w-full overflow-x-auto rounded-lg focus-visible:outline-2 focus-visible:outline-ring/60"
      tabIndex={0}
      role="group"
      aria-label={caption}
    >
      <table
        className={cn(
          'w-full caption-bottom border-collapse text-sm',
          '[&_thead_th]:h-10 [&_thead_th]:px-3 [&_thead_th]:text-left [&_thead_th]:align-middle',
          '[&_thead_th]:font-medium [&_thead_th]:text-muted-foreground',
          '[&_thead_tr]:border-b [&_thead_tr]:border-border',
          '[&_tbody_tr]:border-b [&_tbody_tr]:border-border [&_tbody_tr:last-child]:border-0',
          '[&_tbody_tr]:transition-colors hover:[&_tbody_tr]:bg-muted/50',
          '[&_td]:p-3 [&_td]:align-middle',
          className,
        )}
      >
        <caption className="sr-only">{caption}</caption>
        {children}
      </table>
    </div>
  );
  /* eslint-enable jsx-a11y/no-noninteractive-tabindex */
}

export interface ThProps extends ThHTMLAttributes<HTMLTableCellElement> {
  numeric?: boolean;
  children?: ReactNode;
}

export function Th({ numeric, className, children, ...rest }: ThProps): React.ReactElement {
  return (
    <th
      scope="col"
      className={cn(numeric && 'text-right tabular-nums', className)}
      {...rest}
    >
      {children}
    </th>
  );
}

export interface TdProps extends TdHTMLAttributes<HTMLTableCellElement> {
  numeric?: boolean;
  nowrap?: boolean;
  primary?: boolean;
  children?: ReactNode;
}

export function Td({
  numeric,
  nowrap,
  primary,
  className,
  children,
  ...rest
}: TdProps): React.ReactElement {
  return (
    <td
      className={cn(
        numeric && 'text-right tabular-nums',
        nowrap && 'whitespace-nowrap',
        primary && 'font-medium text-foreground',
        className,
      )}
      {...rest}
    >
      {children}
    </td>
  );
}

export interface PaginationProps {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  busy?: boolean;
}

export function Pagination({
  page,
  size,
  totalElements,
  totalPages,
  onPageChange,
  busy = false,
}: PaginationProps): React.ReactElement | null {
  const t = useT();
  if (totalPages <= 1) return null;

  const from = page * size + 1;
  const to = Math.min((page + 1) * size, totalElements);

  return (
    <nav
      className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-3 py-3"
      aria-label="Постраничная навигация"
    >
      <span className="text-xs text-muted-foreground tabular-nums">
        {formatCount(from)}–{formatCount(to)} {t('common.of')} {formatCount(totalElements)}
      </span>
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          icon="chevronLeft"
          disabled={page <= 0 || busy}
          onClick={() => onPageChange(page - 1)}
          aria-label="Предыдущая страница"
        >
          Назад
        </Button>
        <span className="text-xs text-muted-foreground tabular-nums" aria-live="polite">
          {t('common.page')} {formatCount(page + 1)} {t('common.of')} {formatCount(totalPages)}
        </span>
        <Button
          size="sm"
          iconAfter="chevronRight"
          disabled={page >= totalPages - 1 || busy}
          onClick={() => onPageChange(page + 1)}
          aria-label="Следующая страница"
        >
          Вперёд
        </Button>
      </div>
    </nav>
  );
}
