import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Icon, type IconName } from './Icon';

export interface EmptyStateProps {
  title: string;
  description?: ReactNode;
  /** What the user can do about it. */
  action?: ReactNode;
  icon?: IconName;
  compact?: boolean;
}

export function EmptyState({
  title,
  description,
  action,
  icon = 'search',
  compact = false,
}: EmptyStateProps): React.ReactElement {
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 text-center',
        compact ? 'py-6' : 'py-12',
      )}
    >
      <span className="flex size-10 items-center justify-center rounded-full bg-muted text-muted-foreground">
        <Icon name={icon} size={20} />
      </span>
      <p className="text-sm font-medium">{title}</p>
      {description ? (
        <p className="max-w-prose text-sm text-muted-foreground">{description}</p>
      ) : null}
      {action ? <div className="mt-1 flex flex-wrap justify-center gap-2">{action}</div> : null}
    </div>
  );
}
