/**
 * The single way this app shows a failure.
 *
 * NFR-U3: cause + next action always visible; technical detail (trace id, server
 * `detail`, contract issues) only behind a disclosure so it never shouts at an
 * analyst but is one click away for support.
 */
import { cn } from '@/lib/cn';
import { toUiError } from '@/lib/errors';
import { formatDuration } from '@/lib/format';
import { useT } from '@/lib/i18n/useT';
import { Button } from './Button';
import { Icon } from './Icon';

export interface ErrorStateProps {
  error: unknown;
  /** Retry handler. Omit for errors that retrying cannot fix. */
  onRetry?: () => void;
  /** Extra action (e.g. "back to list"). */
  secondaryAction?: React.ReactNode;
  compact?: boolean;
  /** Overrides the derived title. */
  title?: string;
}

export function ErrorState({
  error,
  onRetry,
  secondaryAction,
  compact = false,
  title,
}: ErrorStateProps): React.ReactElement {
  const t = useT();
  const ui = toUiError(error);
  const showRetry = onRetry !== undefined && ui.retryable;

  return (
    <div
      className={cn(
        'flex flex-col items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-6 text-center',
        compact ? 'py-6' : 'py-10',
      )}
      role="alert"
    >
      <span className="flex size-10 items-center justify-center rounded-full bg-destructive/10 text-destructive">
        <Icon name="alert" size={20} />
      </span>

      <p className="text-sm font-medium">{title ?? ui.title}</p>
      <p className="max-w-prose text-sm text-muted-foreground">{ui.message}</p>
      <p className="max-w-prose text-sm text-muted-foreground">{ui.action}</p>

      {ui.retryAfterSeconds !== null ? (
        <p className="max-w-prose text-sm text-muted-foreground">
          Повторить можно через {formatDuration(ui.retryAfterSeconds)}.
        </p>
      ) : null}

      {ui.fieldErrors.length > 0 ? (
        <div className="mt-1 flex w-full max-w-prose flex-col gap-1 text-left">
          {ui.fieldErrors.map((fieldError) => (
            <span
              className="flex flex-wrap gap-1.5 text-xs text-muted-foreground"
              key={`${fieldError.field}:${fieldError.message}`}
            >
              <span className="font-mono font-medium text-foreground">{fieldError.field}</span>
              <span>{fieldError.message}</span>
            </span>
          ))}
        </div>
      ) : null}

      {(showRetry || secondaryAction) && (
        <div className="mt-1 flex flex-wrap justify-center gap-2">
          {showRetry ? (
            <Button variant="secondary" icon="refresh" onClick={onRetry}>
              {t('common.retry')}
            </Button>
          ) : null}
          {secondaryAction}
        </div>
      )}

      {(ui.detail ?? ui.traceId ?? ui.status) !== null && (ui.detail ?? ui.traceId ?? ui.status) ? (
        <details className="mt-2 w-full max-w-prose text-left">
          <summary className="cursor-pointer text-xs text-muted-foreground underline-offset-4 hover:underline">
            {t('error.details')}
          </summary>
          <div className="mt-2 overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs whitespace-pre-wrap text-muted-foreground">
            {ui.status !== null ? `HTTP ${ui.status}\n` : ''}
            {ui.slug ? `type: ${ui.slug}\n` : ''}
            {ui.traceId ? `${t('error.traceId')}: ${ui.traceId}\n` : ''}
            {ui.detail ?? ''}
          </div>
        </details>
      ) : null}
    </div>
  );
}
