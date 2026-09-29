import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * Карточка на основе shadcn/ui.
 *
 * Интерфейс прежний: `Card` / `CardHeader` / `CardBody`, где заголовок принимает `title`,
 * `description` и `actions` пропсами, а не композицией. Это отличие от эталонного shadcn сделано
 * намеренно и остаётся: оно не даёт разложить заголовок карточки по-разному на каждой странице, а
 * `as` заставляет автора выбрать уровень заголовка — иначе структура страницы для читающего с экрана
 * рассыпается в набор одинаковых h2.
 */
export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** Отклик на наведение. Только когда действие вызывает вся карточка целиком. */
  interactive?: boolean;
  /** Отступы прямо на карточке — для случая без заголовка и тела. */
  padded?: boolean;
  children: ReactNode;
}

export function Card({
  interactive = false,
  padded = false,
  className,
  children,
  ...rest
}: CardProps): React.ReactElement {
  return (
    <div
      className={cn(
        'flex flex-col rounded-xl border border-border bg-card text-card-foreground shadow-sm',
        interactive && 'transition-shadow hover:shadow-md focus-within:shadow-md',
        padded && 'p-6',
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
}

export interface CardHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  /** Уровень заголовка — чтобы у страницы оставалась верная структура. */
  as?: 'h2' | 'h3' | 'h4';
  id?: string;
}

export function CardHeader({
  title,
  description,
  actions,
  as: Heading = 'h2',
  id,
}: CardHeaderProps): React.ReactElement {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border px-6 py-4">
      <div className="flex min-w-0 flex-col gap-1">
        <Heading className="text-base font-semibold leading-none tracking-tight" id={id}>
          {title}
        </Heading>
        {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function CardBody({
  children,
  flush = false,
  className,
}: {
  children: ReactNode;
  flush?: boolean;
  className?: string;
}): React.ReactElement {
  return <div className={cn(flush ? 'p-0' : 'p-6', className)}>{children}</div>;
}
