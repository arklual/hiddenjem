/**
 * Badges for the contract's enums.
 *
 * Colour assignments follow two different rules on purpose:
 *
 *  - **SourceClass** is a categorical identity → the fixed categorical slot
 *    order, assigned by position in `SOURCE_CLASSES` and never cycled or
 *    reassigned when a filter narrows the set. Three of the light-mode steps sit
 *    below 3:1 on the light surface, so every badge always renders its text
 *    label; the hue dot is a second cue, never the only one.
 *  - **LifecycleStage** is *ordinal* (embryonic → maturing) → the sequential blue
 *    ramp, which darkens with maturity in light mode and lightens in dark mode.
 *
 * Statuses use the reserved status palette and always ship icon + label.
 */
import {
  SOURCE_CLASSES,
  type Credibility,
  type IngestionRunStatus,
  type LifecycleStage,
  type ResearchStatus,
  type SourceClass,
  type FeedbackVerdict,
} from '@/api/types';
import { Badge, type BadgeTone } from '../Badge';
import type { IconName } from '../Icon';
import { useT } from '@/lib/i18n/useT';

/** Categorical slot per source class, fixed by contract order. */
const SOURCE_CLASS_HUE: Record<SourceClass, string> = {
  PREPRINT: 'var(--chart-1)',
  JOURNAL_ARTICLE: 'var(--chart-2)',
  PATENT: 'var(--chart-3)',
  CODE_REPOSITORY: 'var(--chart-4)',
  NEWS: 'var(--chart-5)',
  ANALYST_REPORT: 'var(--chart-6)',
  STANDARD: 'var(--chart-7)',
};

/** Compile-time check that the hue map covers the contract order exactly. */
const _sourceClassCoverage: readonly SourceClass[] = SOURCE_CLASSES;
void _sourceClassCoverage;

/** Ordinal ramp — darkens (light mode) / lightens (dark mode) with maturity. */
const LIFECYCLE_HUE: Record<LifecycleStage, string> = {
  EMBRYONIC: 'var(--seq-400)',
  EMERGING: 'var(--seq-500)',
  ACCELERATING: 'var(--seq-600)',
  MATURING: 'var(--seq-700)',
};

export function SourceClassBadge({
  sourceClass,
  size = 'sm',
}: {
  sourceClass: SourceClass;
  size?: 'sm' | 'lg';
}): React.ReactElement {
  const t = useT();
  return (
    <Badge dotColor={SOURCE_CLASS_HUE[sourceClass]} size={size} outline>
      {t(`sourceClass.${sourceClass}`)}
    </Badge>
  );
}

/**
 * Уровень доверенности источника — требование ТЗ к каждому источнику.
 *
 * Шкала порядковая (пониженная → высокая), поэтому цвет берётся из последовательной рампы, как у
 * стадии жизненного цикла, а не из категориальных слотов: категориальный цвет сообщал бы, что
 * уровни равноправны, а они упорядочены.
 *
 * Основание уровня уходит в `title`, а не в подпись: правило — предложение, а не ярлык, и в строке
 * таблицы оно вытеснило бы название источника. Рядом с подписью оно всё равно доступно наведением
 * и чтением с экрана.
 */
const CREDIBILITY_HUE: Record<Credibility, string> = {
  HIGH: 'var(--seq-600)',
  MEDIUM: 'var(--seq-400)',
  LOW: 'var(--seq-200)',
};

export function CredibilityBadge({
  credibility,
  basis,
  size = 'sm',
}: {
  credibility: Credibility;
  basis?: string | null;
  size?: 'sm' | 'lg';
}): React.ReactElement {
  const t = useT();
  return (
    <Badge
      dotColor={CREDIBILITY_HUE[credibility]}
      size={size}
      outline
      title={basis && basis.length > 0 ? basis : t(`credibilityHint.${credibility}`)}
    >
      {t('evidence.credibility')}: {t(`credibility.${credibility}`)}
    </Badge>
  );
}

export function LifecycleBadge({
  stage,
  size = 'sm',
}: {
  stage: LifecycleStage;
  size?: 'sm' | 'lg';
}): React.ReactElement {
  const t = useT();
  return (
    <Badge dotColor={LIFECYCLE_HUE[stage]} size={size} title={t(`lifecycleHint.${stage}`)}>
      {t(`lifecycle.${stage}`)}
    </Badge>
  );
}

const RESEARCH_STATUS_TONE: Record<ResearchStatus, { tone: BadgeTone; icon?: IconName }> = {
  PENDING: { tone: 'neutral', icon: 'clock' },
  COLLECTING: { tone: 'accent', icon: 'database' },
  ANALYZING: { tone: 'accent', icon: 'flask' },
  ASSEMBLING: { tone: 'accent', icon: 'formula' },
  COMPLETED: { tone: 'good', icon: 'checkCircle' },
  FAILED: { tone: 'critical', icon: 'error' },
  CANCELLED: { tone: 'neutral', icon: 'close' },
};

export function ResearchStatusBadge({ status }: { status: ResearchStatus }): React.ReactElement {
  const t = useT();
  const config = RESEARCH_STATUS_TONE[status];
  return (
    <Badge tone={config.tone} icon={config.icon}>
      {t(`researchStatus.${status}`)}
    </Badge>
  );
}

const RUN_STATUS_TONE: Record<IngestionRunStatus, { tone: BadgeTone; icon: IconName }> = {
  RUNNING: { tone: 'accent', icon: 'refresh' },
  COMPLETED: { tone: 'good', icon: 'checkCircle' },
  PARTIAL: { tone: 'warning', icon: 'alert' },
  FAILED: { tone: 'critical', icon: 'error' },
};

export function RunStatusBadge({ status }: { status: IngestionRunStatus }): React.ReactElement {
  const t = useT();
  const config = RUN_STATUS_TONE[status];
  return (
    <Badge tone={config.tone} icon={config.icon}>
      {t(`runStatus.${status}`)}
    </Badge>
  );
}

const VERDICT_TONE: Record<FeedbackVerdict, { tone: BadgeTone; icon: IconName }> = {
  RELEVANT: { tone: 'good', icon: 'checkCircle' },
  NOISE: { tone: 'critical', icon: 'close' },
  ALREADY_KNOWN: { tone: 'neutral', icon: 'info' },
};

export function VerdictBadge({
  verdict,
  carried = false,
}: {
  verdict: FeedbackVerdict;
  carried?: boolean;
}): React.ReactElement {
  const t = useT();
  const config = VERDICT_TONE[verdict];
  return (
    <Badge
      tone={carried ? 'neutral' : config.tone}
      icon={config.icon}
      // Приглушённый тон и подпись: это суждение аналитика, но сделанное в прошлой версии, и выдать
      // его за здешнее значило бы поставить пометку от его имени там, где он её не ставил.
      title={carried ? t('verdict.carriedHint') : undefined}
    >
      {carried ? `${t(`verdict.${verdict}`)} · ${t('verdict.carried')}` : t(`verdict.${verdict}`)}
    </Badge>
  );
}
