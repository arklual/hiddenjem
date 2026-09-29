/**
 * Hand-written aliases over the generated contract types.
 *
 * `generated.ts` is produced by `npm run gen:api` from
 * `contracts/openapi/horizon-api.yaml` and must never be edited. This module is
 * the only place allowed to reach into its nested `components['schemas'][...]`
 * shape, so the rest of the app imports flat, readable names.
 */
import type { components } from './generated';

export type Schemas = components['schemas'];

/* ── Errors ──────────────────────────────────────────────────── */
export type Problem = Schemas['Problem'];
export type ProblemFieldError = NonNullable<Problem['errors']>[number];

/* ── Направления ─────────────────────────────────────────────── */
export type DirectionResolution = Schemas['DirectionResolution'];

/* ── Research ────────────────────────────────────────────────── */
export type SourceClass = Schemas['SourceClass'];
export type ResearchStatus = Schemas['ResearchStatus'];
export type LifecycleStage = Schemas['LifecycleStage'];
export type AnalysisParameters = Schemas['AnalysisParameters'];
/** Режим анализа: `fast` — в пределах SLA 20 минут, `quality` — 30–40 минут. */
export type AnalysisMode = NonNullable<AnalysisParameters['mode']>;
export type SubmitResearchRequest = Schemas['SubmitResearchRequest'];
export type AnalysisProgress = Schemas['AnalysisProgress'];
export type AnalysisStage = AnalysisProgress['stage'];
export type FailureInfo = Schemas['FailureInfo'];
export type ResearchRequestView = Schemas['ResearchRequestView'];
export type ResearchRequestPage = Schemas['ResearchRequestPage'];

/* ── Report ──────────────────────────────────────────────────── */
export type IndicatorScore = Schemas['IndicatorScore'];
export type IndicatorName = IndicatorScore['name'];
export type EmergenceAssessment = Schemas['EmergenceAssessment'];
export type TimelinePoint = Schemas['TimelinePoint'];
export type Evidence = Schemas['Evidence'];
/** Уровень доверенности источника — требование ТЗ к отображению каждого источника. */
export type Credibility = NonNullable<Evidence['credibility']>;
/** Русский слой карточки: перевод рядом с оригиналом, с именем модели (ADR-0017). */
export type Localization = Schemas['Localization'];
export type Motivation = Schemas['Motivation'];
export type MotivationAttribution = NonNullable<Motivation['attributions']>[number];
export type CaseExample = Schemas['CaseExample'];
export type OrganizationType = NonNullable<CaseExample['organizationType']>;
export type RankedTrend = Schemas['RankedTrend'];
export type Coverage = Schemas['Coverage'];
/** Одна причина, по которой кандидаты не попали в отчёт: код, формулировка, число, примеры. */
export type Exclusion = NonNullable<Coverage['exclusions']>[number];
export type TrendReport = Schemas['TrendReport'];

/* ── Feedback ────────────────────────────────────────────────── */
export type TrendFeedbackRequest = Schemas['TrendFeedbackRequest'];
export type TrendFeedbackView = Schemas['TrendFeedbackView'];
export type FeedbackVerdict = TrendFeedbackView['verdict'];

/* ── Saved domains ───────────────────────────────────────────── */
export type SavedDomain = Schemas['SavedDomain'];
export type SaveDomainRequest = Schemas['SaveDomainRequest'];

/* ── Features and radar ──────────────────────────────────────── */
export type FeatureFlag = Schemas['FeatureFlag'];
export type RadarRefresh = Schemas['RadarRefresh'];
export type RadarDigest = Schemas['RadarDigest'];
export type DirectionOverlap = Schemas['DirectionOverlap'];
export type TopicSearchResult = Schemas['TopicSearchResult'];
export type FoundTopic = Schemas['FoundTopic'];
export type Quota = Schemas['Quota'];
export type SharedTopic = Schemas['SharedTopic'];
export type DirectionPortrait = Schemas['DirectionPortrait'];
export type ReportDelta = Schemas['ReportDelta'];
export type ReportDeltaEntry = Schemas['ReportDeltaEntry'];
export type TermExplanation = Schemas['TermExplanation'];
export type TermExplanationPending = Schemas['TermExplanationPending'];
export type TermTrace = TermExplanation['traces'][number];

/* ── Sources ─────────────────────────────────────────────────── */
export type SourceView = Schemas['SourceView'];
export type OpenAlexQuota = Schemas['OpenAlexQuota'];
export type UpdateSourceRequest = Schemas['UpdateSourceRequest'];
export type TriggerIngestionRequest = Schemas['TriggerIngestionRequest'];
export type IngestionRunView = Schemas['IngestionRunView'];
export type IngestionRunPage = Schemas['IngestionRunPage'];
export type IngestionRunStatus = IngestionRunView['status'];

/* ── Словарь направлений ─────────────────────────────────────── */
export type UnrecognizedDirection = Schemas['UnrecognizedDirection'];

/* ── Enum value lists ─────────────────────────────────────────
   Declared `as const` and typed so a contract change that renames or drops a
   member breaks the build here rather than silently at runtime. */
export const SOURCE_CLASSES = [
  'PREPRINT',
  'JOURNAL_ARTICLE',
  'PATENT',
  'CODE_REPOSITORY',
  'NEWS',
  'ANALYST_REPORT',
  'STANDARD',
] as const satisfies readonly SourceClass[];

export const RESEARCH_STATUSES = [
  'PENDING',
  'COLLECTING',
  'ANALYZING',
  'ASSEMBLING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const satisfies readonly ResearchStatus[];

export const ANALYSIS_STAGES = [
  'QUEUED',
  'COLLECTING',
  'ANALYZING',
  'ASSEMBLING',
  'DONE',
] as const satisfies readonly AnalysisStage[];

export const LIFECYCLE_STAGES = [
  'EMBRYONIC',
  'EMERGING',
  'ACCELERATING',
  'MATURING',
] as const satisfies readonly LifecycleStage[];

/** Methodology §4 order — the order indicators are always presented in. */
export const INDICATOR_NAMES = [
  'novelty',
  'growth',
  'diffusion',
  'weakness',
  'coherence',
  'impact',
] as const satisfies readonly IndicatorName[];

export const FEEDBACK_VERDICTS = [
  'RELEVANT',
  'NOISE',
  'ALREADY_KNOWN',
] as const satisfies readonly FeedbackVerdict[];

/** Statuses after which no further progress event can arrive (domain model I6). */
export const TERMINAL_RESEARCH_STATUSES = [
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const satisfies readonly ResearchStatus[];

export function isTerminalStatus(status: ResearchStatus): boolean {
  return (TERMINAL_RESEARCH_STATUSES as readonly ResearchStatus[]).includes(status);
}

/** BRULE-6: confidence below this threshold marks a trend as low-evidence. */
export const LOW_EVIDENCE_CONFIDENCE_THRESHOLD = 0.4;
