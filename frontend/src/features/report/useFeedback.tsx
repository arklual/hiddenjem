/**
 * Оценка темы аналитиком: «Релевантна», «Уже знаем», «Не технология».
 *
 * Оценка уходит на сервер (`PUT …/feedback`) и живёт в отчёте: «не технология» убирает тему из
 * следующего прогона ещё до отбора. Поэтому эта пометка требует подтверждения, объясняет
 * последствия и снимается прямо из уведомления. Повторное нажатие на ту же оценку снимает её
 * (`DELETE …/feedback`) — «я ошибся», а не «тема полезна».
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api/client';
import { queryKeys } from '@/api/queryKeys';
import type { FeedbackVerdict, RankedTrend, TrendFeedbackView, TrendReport } from '@/api/types';
import { useToast } from '@/components/toast/ToastContext';
import { toUiError } from '@/lib/errors';
import { useDialog } from '@/ui/DialogProvider';
import { HjIcon } from '@/ui/HjIcon';
import { topicTitle } from './topicModel';

interface Input {
  trend: RankedTrend;
  verdict: FeedbackVerdict | null;
}

export function useFeedback(
  report: TrendReport,
): (trend: RankedTrend, verdict: FeedbackVerdict) => void {
  const queryClient = useQueryClient();
  const toast = useToast();
  const dialog = useDialog();

  const patch = (trendKey: string, feedback: TrendFeedbackView | undefined): void => {
    queryClient.setQueryData<TrendReport>(queryKeys.report(report.id), (current) =>
      current
        ? {
            ...current,
            trends: current.trends.map((trend) => {
              if (trend.trendKey !== trendKey) return trend;
              const { feedback: _dropped, ...rest } = trend;
              return feedback ? { ...rest, feedback } : rest;
            }),
          }
        : current,
    );
  };

  const mutation = useMutation({
    mutationFn: async ({ trend, verdict }: Input): Promise<TrendFeedbackView | undefined> => {
      if (verdict === null) {
        await api.withdrawTrendFeedback(report.id, trend.trendKey);
        return undefined;
      }
      return api.submitTrendFeedback(report.id, trend.trendKey, { verdict });
    },
    onSuccess: (feedback, { trend, verdict }) => {
      patch(trend.trendKey, feedback);
      const undo = { label: 'Отменить', onClick: () => mutation.mutate({ trend, verdict: null }) };
      if (verdict === null) toast.show('Оценка снята');
      else if (verdict === 'NOISE')
        toast.show('Тема помечена. Она исчезнет из следующего прогона.', { action: undo });
      else if (verdict === 'RELEVANT')
        toast.show('Тема отмечена как релевантная', { action: undo });
      else
        toast.show('Отмечено «уже знаем»', {
          description: 'Тема останется в отчётах: она настоящая, просто не новость для вас.',
          action: undo,
        });
    },
    onError: (error) =>
      toast.show('Оценка не сохранена', {
        tone: 'critical',
        description: toUiError(error).message,
      }),
  });

  return (trend, verdict) => {
    if (trend.feedback?.verdict === verdict && !trend.feedback.carried) {
      mutation.mutate({ trend, verdict: null });
      return;
    }
    if (verdict !== 'NOISE') {
      mutation.mutate({ trend, verdict });
      return;
    }
    const { title } = topicTitle(trend);
    dialog.open({
      title: `Пометить «${title}» как «не технология»?`,
      body: (
        <>
          <p>
            Тема исчезнет из следующего прогона направления ещё до отбора в ТОП, поэтому её место
            займёт следующий кандидат. Над списком появится «Скрыто тем: 1».
          </p>
          <p className="body-muted">
            Пометка действует только для вас: коллеги тему по-прежнему увидят. Снять её можно в
            любой момент.
          </p>
        </>
      ),
      actions: [
        { label: 'Отмена', autoFocus: true },
        {
          label: 'Пометить',
          primary: true,
          onClick: () => mutation.mutate({ trend, verdict: 'NOISE' }),
        },
      ],
    });
  };
}

const OPTIONS: ReadonlyArray<[FeedbackVerdict, string]> = [
  ['RELEVANT', 'Релевантна'],
  ['ALREADY_KNOWN', 'Уже знаем'],
  ['NOISE', 'Не технология'],
];

export function FeedbackChips({
  trend,
  onVerdict,
  idPrefix,
}: {
  trend: RankedTrend;
  onVerdict: (trend: RankedTrend, verdict: FeedbackVerdict) => void;
  idPrefix: string;
}): React.ReactElement {
  const current = trend.feedback?.verdict;
  const labelId = `${idPrefix}-fb-${trend.rank}`;
  return (
    <div className="feedback" role="group" aria-labelledby={labelId}>
      <span className="section-label" id={labelId}>
        Ваша оценка
        {trend.feedback?.carried ? (
          <span className="caption"> · из прошлой версии отчёта</span>
        ) : null}
      </span>
      <div className="feedback__chips">
        {OPTIONS.map(([verdict, label]) => (
          <button
            key={verdict}
            type="button"
            className="chip"
            aria-pressed={current === verdict}
            onClick={() => onVerdict(trend, verdict)}
          >
            {current === verdict ? <HjIcon name="check" size={16} /> : null}
            {label}
          </button>
        ))}
      </div>
    </div>
  );
}
