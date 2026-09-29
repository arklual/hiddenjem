import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';
import { Icon } from './Icon';

export interface Breadcrumb {
  label: string;
  to?: string;
}

export interface PageHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  /** Small facts under the description (dates, counts, versions). */
  meta?: ReactNode;
  actions?: ReactNode;
  breadcrumbs?: readonly Breadcrumb[];
}

export function PageHeader({
  title,
  description,
  meta,
  actions,
  breadcrumbs,
}: PageHeaderProps): React.ReactElement {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex min-w-0 flex-col gap-2">
        {breadcrumbs && breadcrumbs.length > 0 ? (
          <nav
            className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground [&_a]:underline-offset-4 [&_a:hover]:underline"
            aria-label="Хлебные крошки"
          >
            {breadcrumbs.map((crumb, index) => (
              <span key={`${crumb.label}-${index}`} className="inline-flex items-center gap-1.5">
                {index > 0 ? <Icon name="chevronRight" size={12} /> : null}
                {crumb.to ? <Link to={crumb.to}>{crumb.label}</Link> : <span>{crumb.label}</span>}
              </span>
            ))}
          </nav>
        ) : null}
        <h1 className="text-2xl font-semibold tracking-tight text-balance">{title}</h1>
        {description ? (
          <p className="max-w-prose text-sm text-muted-foreground">{description}</p>
        ) : null}
        {meta ? (
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
            {meta}
          </div>
        ) : null}
      </div>
      {/* `shrink-0` не давал панели сжаться, а без сжатия перенос не срабатывал: на узком экране
          четыре кнопки выезжали за край страницы. Ширина ограничена содержимым — заголовку она не
          мешает, а кнопки переносятся, когда места не хватает. */}
      {actions ? (
        <div className="flex min-w-0 flex-wrap items-center gap-2 sm:shrink-0">{actions}</div>
      ) : null}
    </div>
  );
}

export function PageStack({
  children,
  tight = false,
  className,
}: {
  children: ReactNode;
  tight?: boolean;
  className?: string;
}): React.ReactElement {
  return <div className={cn('flex flex-col', tight ? 'gap-4' : 'gap-6', className)}>{children}</div>;
}

export function MetaItem({ children }: { children: ReactNode }): React.ReactElement {
  return <span className="inline-flex items-center gap-1.5">{children}</span>;
}
