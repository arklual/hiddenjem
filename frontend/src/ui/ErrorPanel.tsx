/**
 * Ошибка загрузки в облике Hiddenjem: что случилось, что делать, и технические подробности под
 * раскрытием — поддержке они нужны, аналитику мешают.
 */
import { toUiError } from '@/lib/errors';
import { HjIcon } from './HjIcon';

export function ErrorPanel({
  error,
  onRetry,
  title,
}: {
  error: unknown;
  onRetry?: () => void;
  title?: string;
}): React.ReactElement {
  const ui = toUiError(error);
  const technical = [
    ui.status ? `HTTP ${ui.status}` : null,
    ui.detail,
    ui.traceId ? `trace ${ui.traceId}` : null,
  ]
    .filter(Boolean)
    .join('\n');
  return (
    <div className="notice notice--danger mb-6" role="alert">
      <HjIcon name="alert" />
      <div className="od-stack od-fill" style={{ '--od-gap': '6px' } as React.CSSProperties}>
        <strong>{title ?? ui.title}</strong>
        <span>
          {ui.message} {ui.action}
        </span>
        {technical ? (
          <details className="error-details">
            <summary>Подробности для поддержки</summary>
            <pre>{technical}</pre>
          </details>
        ) : null}
        {onRetry && ui.retryable ? (
          <div>
            <button type="button" className="btn btn--secondary btn--sm" onClick={onRetry}>
              <HjIcon name="refresh" size={16} />
              Повторить
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}
