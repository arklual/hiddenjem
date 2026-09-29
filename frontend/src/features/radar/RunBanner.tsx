/**
 * «Идёт анализ» над радаром и формой нового анализа: запуск не теряется, пока аналитик занят
 * другим, и до его хода — один щелчок.
 */
import { Link } from 'react-router-dom';
import { isRunning, useRecentRequests } from '@/api/queries';
import { directionTitle } from '@/features/report/topicModel';
import { translate } from '@/lib/i18n/translate';
import { HjIcon } from '@/ui/HjIcon';

export function RunBanner(): React.ReactElement | null {
  const requests = useRecentRequests();
  const running = (requests.data?.content ?? []).filter(isRunning);
  if (running.length === 0) return null;
  return (
    <>
      {running.slice(0, 3).map((request) => (
        <Link
          key={request.id}
          className="notice notice--strong run-banner"
          to={`/runs/${request.id}`}
        >
          <HjIcon name="clock" />
          <span className="od-stack" style={{ '--od-gap': '2px' } as React.CSSProperties}>
            <strong>Идёт анализ «{directionTitle(request.query)}»</strong>
            <span>
              {translate(`stage.${request.progress.stage}`)} · готово {request.progress.percent} % ·
              можно заниматься другим
            </span>
          </span>
          <HjIcon name="arrow-right" />
        </Link>
      ))}
    </>
  );
}
