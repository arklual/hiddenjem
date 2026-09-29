import type { CSSProperties } from 'react';
import { cn } from '@/lib/cn';

export interface SkeletonProps {
  width?: string | number;
  height?: string | number;
  radius?: string;
  className?: string;
  style?: CSSProperties;
}

/**
 * Loading placeholder.
 *
 * The whole skeleton region is announced once by its container (`aria-busy` +
 * `aria-live="polite"` on `SkeletonList`), never per-block — otherwise a screen
 * reader would read a dozen meaningless "loading" nodes.
 */
export function Skeleton({
  width = '100%',
  height = '1em',
  radius,
  className,
  style,
}: SkeletonProps): React.ReactElement {
  return (
    <span
      className={cn('block animate-pulse rounded-md bg-muted', className)}
      aria-hidden="true"
      style={{ width, height, borderRadius: radius, ...style }}
    />
  );
}

/** Wraps a set of skeletons and carries the single live-region announcement. */
export function SkeletonList({
  count = 3,
  height = 96,
  label,
}: {
  count?: number;
  height?: number;
  label: string;
}): React.ReactElement {
  return (
    <div className="flex flex-col gap-3" role="status" aria-busy="true" aria-live="polite">
      <span className="sr-only">{label}</span>
      {Array.from({ length: count }, (_, index) => (
        <Skeleton key={index} height={height} radius="var(--radius)" />
      ))}
    </div>
  );
}

export function Spinner({ size = 18 }: { size?: number }): React.ReactElement {
  return (
    <span
      className="inline-block animate-spin rounded-full border-current border-t-transparent text-muted-foreground"
      aria-hidden="true"
      style={{ width: size, height: size, borderWidth: Math.max(2, Math.round(size / 9)) }}
    />
  );
}

export function CenteredSpinner({ label }: { label: string }): React.ReactElement {
  return (
    <div
      className="flex items-center justify-center gap-2.5 py-10 text-sm text-muted-foreground"
      role="status"
      aria-live="polite"
    >
      <Spinner />
      <span>{label}</span>
    </div>
  );
}
