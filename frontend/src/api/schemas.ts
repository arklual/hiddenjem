/**
 * Runtime validation of API responses against the OpenAPI contract.
 *
 * Two deliberate policies:
 *
 * 1. **Structure and enums are enforced; numeric ranges are not.** The contract's
 *    `minimum`/`maximum` on scores are backend guarantees. Re-checking them here
 *    would turn a cosmetic rounding artefact (a `numeric(9,6)` score arriving as
 *    1.0000001) into a blank screen. Values are clamped at render time instead.
 * 2. **Unknown keys are stripped, not rejected.** The backend may add fields
 *    ahead of the frontend; that must not break an analyst mid-session.
 *
 * The `ContractConformance` block at the bottom is a compile-time proof that
 * every schema's parsed output is assignable to the generated contract type, so
 * a contract change that this file has not caught up with fails `tsc`.
 */
import { z } from 'zod';
import type * as T from './types';
import { SOURCE_CLASSES, RESEARCH_STATUSES, LIFECYCLE_STAGES, INDICATOR_NAMES } from './types';

/* ── Primitives ──────────────────────────────────────────────── */

/** ISO-8601 instant. Kept as an opaque string; parsed only by the formatters. */
const isoDateTime = z.string();
/** ISO-8601 calendar date (`YYYY-MM-DD`). */
const isoDate = z.string();
const uuid = z.string();

/**
 * An enum the client only ever *displays*.
 *
 * Strict `z.enum` on such a field turns an additive backend change into an outage: one unknown
 * `sourceClass` in one evidence row of one trend fails the whole report parse, and the analyst
 * gets a blank page mid-session. That is precisely the scenario this module's own header says must
 * not happen. The value is passed through unchanged and the label lookup falls back to it, so an
 * unrecognised member renders as itself rather than breaking the page.
 *
 * The cast is deliberate and confined here: it models an *open* enum, which is what a versioned
 * contract actually has. Enums the client **branches on** — status, indicator name — stay
 * strict below, because there silently accepting an unknown value would change behaviour rather
 * than only a caption.
 */
const openEnum = <T extends readonly [string, ...string[]]>(_known: T) =>
  z.string().transform((value) => value as T[number]);

const sourceClassSchema = openEnum(SOURCE_CLASSES);
const researchStatusSchema = z.enum(RESEARCH_STATUSES);
const lifecycleStageSchema = openEnum(LIFECYCLE_STAGES);
const indicatorNameSchema = z.enum(INDICATOR_NAMES);
const feedbackVerdictSchema = z.enum(['RELEVANT', 'NOISE', 'ALREADY_KNOWN']);

const pageMetaShape = {
  page: z.number().int(),
  size: z.number().int(),
  totalElements: z.number(),
  totalPages: z.number().int(),
};

/* ── Research ────────────────────────────────────────────────── */

/**
 * Движок расчёта. Перечень закрыт: имя вне его — не «другой движок», а расхождение с контрактом,
 * и молча прочитать его как строку значило бы показать в отчёте подпись, которой нет смысла.
 */
export const analysisEngineSchema = z.enum(['methodology', 'signals']);

/**
 * Режим анализа: `fast` укладывается в SLA двадцати минут, `quality` тратит 30–40 минут на более
 * глубокое исследование. Перечень закрыт по той же причине, что и у движка: незнакомое имя —
 * расхождение с контрактом, а не третий режим.
 */
export const analysisModeSchema = z.enum(['fast', 'quality']);

export const analysisParametersSchema = z.object({
  topN: z.number().int().optional(),
  yearsWindow: z.number().int().optional(),
  sourceClasses: z.array(sourceClassSchema).optional(),
  minConfidence: z.number().optional(),
  includeMature: z.boolean().optional(),
  // Без разбора поле терялось бы: `z.object` молча выбрасывает незнакомые ключи, и «повторить»
  // вернуло бы прогон в другом режиме, не сказав об этом ни слова.
  mode: analysisModeSchema.optional(),
});

export const analysisProgressSchema = z.object({
  stage: z.enum(['QUEUED', 'COLLECTING', 'ANALYZING', 'ASSEMBLING', 'DONE']),
  percent: z.number().int(),
  message: z.string().optional(),
  updatedAt: isoDateTime.optional(),
});

export const failureInfoSchema = z.object({
  code: z.string(),
  message: z.string(),
  retryable: z.boolean(),
});

export const researchRequestViewSchema = z.object({
  id: uuid,
  query: z.string(),
  normalizedQuery: z.string().optional(),
  parameters: analysisParametersSchema,
  status: researchStatusSchema,
  progress: analysisProgressSchema,
  reportId: uuid.nullish(),
  partial: z.boolean().optional(),
  fromCache: z.boolean().optional(),
  // Строка, а не закрытый перечень: исход только показывается, и появление нового не должно ронять
  // разбор всего ответа. Приходит лишь в ответе на отправку — при чтении запроса его нет.
  outcome: z.string().optional(),
  // Приходит только в списке. Именно optional, а не `.default(false)`: подстановка false означала бы
  // «это не ваш запрос» там, где сервер ничего не сказал.
  mine: z.boolean().optional(),
  // Идентификатор автора; имя разрешает клиент по справочнику организации. Тоже только в списке.
  requestedBy: uuid.optional(),
  failure: failureInfoSchema.optional(),
  submittedAt: isoDateTime,
  finishedAt: isoDateTime.nullish(),
  etaSeconds: z.number().int().nullish(),
});

export const researchRequestPageSchema = z.object({
  ...pageMetaShape,
  content: z.array(researchRequestViewSchema),
});

/* ── Report ──────────────────────────────────────────────────── */

export const indicatorScoreSchema = z.object({
  name: indicatorNameSchema,
  value: z.number(),
  weight: z.number(),
  multiplier: z.number(),
  shortfallShare: z.number(),
  explanation: z.string().optional(),
  diagnostics: z.record(z.string(), z.unknown()).optional(),
});

export const rankStabilitySchema = z.object({
  best: z.number().int(),
  worst: z.number().int(),
});

export const emergenceAssessmentSchema = z.object({
  score: z.number(),
  confidence: z.number(),
  lowEvidence: z.boolean(),
  indicators: z.array(indicatorScoreSchema),
  // Необязательное: отчёты, выпущенные до появления проверки, диапазона не несут. Отсутствие
  // означает «устойчивость не измерялась», а не «место неустойчиво».
  rankStability: rankStabilitySchema.optional(),
});

export const timelinePointSchema = z.object({
  period: z.string(),
  documentCount: z.number().int(),
  dov: z.number().optional(),
  dod: z.number().optional(),
});

export const evidenceSchema = z.object({
  sourceId: z.string(),
  sourceClass: sourceClassSchema,
  externalId: z.string().optional(),
  title: z.string(),
  authors: z.string().optional(),
  organization: z.string().optional(),
  organizationCountry: z.string().optional(),
  publishedOn: isoDate,
  url: z.string(),
  doi: z.string().optional(),
  citationCount: z.number().int().nullish(),
  relevance: z.number().optional(),
  snippet: z.string().optional(),
  // Доверенность и язык — требование ТЗ к каждому источнику. Без этих строк разбор молча выбрасывал
  // поля, которые сервер присылает, и интерфейсу было нечего показать.
  credibility: openEnum(['HIGH', 'MEDIUM', 'LOW'] as const).optional(),
  credibilityBasis: z.string().optional(),
  independent: z.boolean().optional(),
  language: z.string().optional(),
});

/** Русский слой карточки: перевод рядом с оригиналом и имя модели, которая его сделала. */
export const localizationSchema = z.object({
  title: z.string().optional(),
  titleModel: z.string().optional(),
  titleMode: openEnum(['MACHINE', 'GENERATIVE'] as const).optional(),
  definition: z.string().optional(),
  problem: z.string().optional(),
  benefit: z.string().optional(),
  evidenceTitles: z.array(z.string()).optional(),
  textModel: z.string().optional(),
  textMode: openEnum(['MACHINE', 'GENERATIVE'] as const).optional(),
  // Тема как тренд — одно русское предложение о том, что меняется. Генеративный пересказ по
  // источникам темы; отсутствует, когда модель не ответила или ответ не прошёл проверку.
  statement: z.string().optional(),
  statementModel: z.string().optional(),
});

export const motivationSchema = z.object({
  problem: z.string(),
  benefit: z.string(),
  attributions: z
    .array(
      z.object({
        statement: z.enum(['problem', 'benefit']),
        evidenceIndex: z.number().int(),
        // Необязательно: отчёты, выпущенные до появления поля, предложений не несут, и
        // отвергать их целиком было бы хуже, чем показать абзац одной цитатой, как раньше.
        sentence: z.string().optional(),
      }),
    )
    .optional(),
});

export const caseExampleSchema = z.object({
  organization: z.string(),
  organizationType: z
    .enum(['COMPANY', 'UNIVERSITY', 'RESEARCH_INSTITUTE', 'GOVERNMENT', 'NONPROFIT'])
    .optional(),
  country: z.string().optional(),
  summary: z.string().optional(),
  evidenceIndex: z.number().int(),
  // Необязательно намеренно: отчёты, выпущенные до появления поля, основания не несут, и
  // отвергать их целиком из-за одной подписи было бы хуже, чем показать её осторожной.
  basis: z.enum(['PATENT', 'CORPORATE_PUBLICATION', 'ACADEMIC_GROUP']).optional(),
});

export const trendFeedbackViewSchema = z.object({
  verdict: feedbackVerdictSchema,
  comment: z.string().optional(),
  createdAt: isoDateTime,
  // Оценка из более ранней версии отчёта по тому же направлению. Опциональна, потому что старый
  // сервер её не пришлёт, и отсутствие означает «дана здесь» — то же, что false.
  carried: z.boolean().optional(),
});

export const rankedTrendSchema = z.object({
  rank: z.number().int(),
  trendKey: z.string(),
  title: z.string(),
  definition: z.string(),
  motivation: motivationSchema,
  caseExample: caseExampleSchema.optional(),
  assessment: emergenceAssessmentSchema,
  lifecycleStage: lifecycleStageSchema,
  firstMentionYear: z.number().int(),
  totalDocuments: z.number().int(),
  burst: z
    .object({
      startPeriod: z.string().nullish(),
      weight: z.number().nullish(),
    })
    .optional(),
  timeline: z.array(timelinePointSchema),
  evidence: z.array(evidenceSchema),
  feedback: trendFeedbackViewSchema.optional(),
  // Доля документов темы, отнесённых к направлению отчёта. Необязательная: отчёты, выпущенные до
  // появления величины, её не несут, и отсутствие означает «не измеряли», а не «ноль».
  directionShare: z.number().optional(),
  localization: localizationSchema.optional(),
  lowCredibilityOnly: z.boolean().optional(),
  // Почему тема — слабый сигнал и почему такая уверенность. Необязательное: у отчётов, выпущенных до
  // появления объяснения, его нет.
  explanation: z.array(z.object({ title: z.string(), text: z.string() })).optional(),
});

export const coverageSchema = z.object({
  documentsAnalyzed: z.number().int(),
  candidatesEvaluated: z.number().int().optional(),
  sourcesUsed: z.array(z.string()),
  unavailableSources: z.array(z.string()).optional(),
  partial: z.boolean(),
  // Необязательное намеренно: поле аддитивное, и отчёт, выпущенный до его появления, обязан
  // читаться как «направление распознано». Оговорка, срабатывающая не по делу, обесценивает
  // настоящие — поэтому в интерфейсе проверяется именно `=== false`, а не отсутствие.
  directionRecognized: z.boolean().optional(),
  directionSuggestions: z.array(z.string()).optional(),
  // Сколько тем скрыто по пометке аналитика «это не технология». Необязательное по той же причине,
  // что и directionRecognized: отчёты, выпущенные до появления пометок, скрытых тем не имели.
  suppressedByAnalyst: z.number().int().nonnegative().optional(),
  // Корпус обрезан пределом профиля: часть литературы не рассматривалась. Необязательное — отчёты,
  // выпущенные до появления поля, его не несут, а отсутствие означает «не обрезан», а не «неизвестно».
  corpusTruncated: z.boolean().optional(),
  windowFrom: isoDate.optional(),
  windowTo: isoDate.optional(),
  // Причины отсева с числами и примерами — «куда делась моя тема?». Прежде поле не разбиралось, и
  // вкладка отсеянного видела пустоту там, где сервер присылал ответ.
  exclusions: z
    .array(
      z.object({
        code: z.string(),
        reason: z.string(),
        count: z.number().int(),
        examples: z.array(z.string()).optional(),
      }),
    )
    .optional(),
});

const directionPortraitSchema = z.object({
  trendsInReport: z.number().int(),
  candidatesEvaluated: z.number().int(),
  lowEvidenceCount: z.number().int(),
  medianFirstMentionYear: z.number().int().nullish(),
  // Stage keys stay open: the client only displays them, and an unknown stage must render as
  // itself rather than fail the whole report parse.
  byLifecycleStage: z.array(z.object({ stage: z.string(), count: z.number().int() })),
  windowFrom: isoDate,
  windowTo: isoDate,
  sourcesUsed: z.array(z.string()),
  unavailableSources: z.array(z.string()),
  partial: z.boolean(),
  directionRecognized: z.boolean().optional(),
});

/**
 * Известные формулировки направлений.
 *
 * Список открытый: свободный ввод остаётся, потому что продукт обязан принимать направления,
 * которых словарь ещё не знает. Поэтому и разбор снисходителен — незнакомая строка это не ошибка.
 */
export const knownDirectionsSchema = z.array(z.string());

/**
 * Вердикт по формулировке направления — до запуска анализа.
 *
 * `recognized: true` приходит и тогда, когда движок недоступен: вердикта в этом случае нет, а
 * показать «не распознано» значило бы соврать. Сомнение трактуется в пользу аналитика — запустить
 * он может в любом случае.
 */
export const directionResolutionSchema = z.object({
  recognized: z.boolean(),
  // Умолчание, а не обязательное поле: ответ без подсказок — обычный случай, и падать на нём
  // разбор не должен.
  suggestions: z.array(z.string()).default([]),
});

export const featureFlagSchema = z.object({
  key: z.string(),
  title: z.string(),
  description: z.string().optional(),
  enabled: z.boolean(),
  source: z.string().optional(),
});

export const radarRefreshSchema = z.object({
  savedDomainId: uuid,
  query: z.string(),
  // Open on purpose: a new outcome must not fail the parse of the whole batch.
  outcome: z.string(),
  requestId: uuid.optional(),
  reason: z.string().optional(),
});

export const radarDigestSchema = z.object({
  savedDomainId: uuid,
  query: z.string(),
  reportId: uuid.optional(),
  portrait: directionPortraitSchema.optional(),
  // Темы, скрытые по пометке смотрящего. Необязательное: отчёты, выпущенные до появления пометок,
  // поля не несут, а оговорка, срабатывающая не по делу, обесценивает настоящие.
  hiddenTopics: z.array(z.object({ trendKey: z.string(), title: z.string() })).optional(),
  entered: z.number().int(),
  left: z.number().int(),
  headline: z.array(z.string()),

  analysedAt: isoDateTime.optional(),
  // Отчёт направления недоступен смотрящему. Необязательное: строки, выпущенные до
  // появления поля, его не несут, а отсутствие означает «доступен», а не «неизвестно».
  reportUnavailable: z.boolean().optional(),
  // Момент последнего визита этого аналитика; отсутствует — значит ни разу, и «новым» направление
  // тогда не считается.
  seenAt: isoDateTime.optional(),
});

export const topicAppearanceSchema = z.object({
  savedDomainId: uuid,
  query: z.string(),
  reportId: uuid,
  rank: z.number().int(),
});

export const sharedTopicSchema = z.object({
  trendKey: z.string(),
  title: z.string(),
  appearances: z.array(topicAppearanceSchema),
});

export const directionOverlapSchema = z.object({
  topics: z.array(sharedTopicSchema),
  directionsCompared: z.number().int(),
  directionsSkipped: z.number().int(),
});

export const topicOccurrenceSchema = z.object({
  reportId: uuid,
  version: z.number().int(),
  query: z.string(),
  rank: z.number().int(),
  generatedAt: isoDateTime,
});

export const foundTopicSchema = z.object({
  trendKey: z.string(),
  title: z.string(),
  directions: z.number().int(),
  bestRank: z.number().int(),
  occurrences: z.array(topicOccurrenceSchema),
  hiddenOccurrences: z.number().int(),
});

export const topicSearchResultSchema = z.object({
  topics: z.array(foundTopicSchema),
  totalTopics: z.number().int(),
  totalOccurrences: z.number().int(),
  truncated: z.boolean(),
});

export const quotaSchema = z.object({
  known: z.boolean(),
  remaining: z.number().int().optional(),
  userRemaining: z.number().int().optional(),
  userLimit: z.number().int().optional(),
  organizationRemaining: z.number().int().optional(),
  organizationLimit: z.number().int().optional(),
});

export const trendReportSchema = z.object({
  id: uuid,
  researchRequestId: uuid,
  version: z.number().int(),
  previousVersionId: uuid.nullish(),
  query: z.string(),
  normalizedQuery: z.string().optional(),
  methodologyVersion: z.string(),
  methodologyProfileId: uuid.optional(),
  scoreAggregator: z.string().optional(),
  // Движок методологии выведен из продукта, но отчёты, посчитанные им, остаются в истории и несут
  // его имя. Поле разбирается, чтобы такой отчёт не падал на разборе, а не ради показа.
  engine: analysisEngineSchema.optional(),
  // Необязательный: отчёты, выпущенные до появления режимов, поля не несут. Все они считались
  // в пределах двадцати минут, то есть быстрым режимом — так их и читает страница отчёта.
  mode: analysisModeSchema.optional(),
  corpusSnapshotId: uuid,
  coverage: coverageSchema,
  truncated: z.boolean().optional(),
  portrait: directionPortraitSchema.optional(),
  trends: z.array(rankedTrendSchema),
  // Темы, скрытые пометкой смотрящего «не технология», — чтобы сказать, что скрыто, а не молчать.
  hiddenTopics: z.array(z.object({ trendKey: z.string(), title: z.string() })).optional(),
  generatedAt: isoDateTime,
});

/* ── Saved domains ───────────────────────────────────────────── */

export const savedDomainSchema = z.object({
  id: uuid,
  query: z.string(),
  normalizedQuery: z.string().optional(),
  parameters: analysisParametersSchema.optional(),
  lastReportId: uuid.nullish(),
  createdAt: isoDateTime,
});

export const savedDomainListSchema = z.array(savedDomainSchema);

const reportDeltaEntrySchema = z.object({
  trendKey: z.string(),
  title: z.string(),
  rank: z.number(),
  // Nullish, not defaulted to zero: a topic present in only one version has no previous rank, and
  // zero would read as "held its position".
  rankChange: z.number().nullish(),
  score: z.number(),
  scoreChange: z.number().nullish(),
});

export const reportDeltaSchema = z.object({
  previousReportId: z.string().optional(),
  previousVersion: z.number().optional(),
  entered: z.array(reportDeltaEntrySchema),
  left: z.array(reportDeltaEntrySchema),
  stayed: z.array(reportDeltaEntrySchema),
  // Open enum on purpose: a reason this build does not know must not fail the whole response.
  unavailableReason: z.string().optional(),
});

/** Трассировка ещё считается (202): тот же запрос повторяется через `retryAfterSeconds`. */
export const termExplanationPendingSchema = z.object({
  state: z.literal('RUNNING'),
  retryAfterSeconds: z.number().int().positive(),
});

export const termExplanationSchema = z.object({
  // Read from the payload, never hardcoded: a client-side copy of the stage list would keep drawing
  // the old journey the day the pipeline gains a stage, and would look right while doing it.
  stages: z.array(z.string()),
  traces: z.array(
    z.object({
      term: z.string(),
      canonicalTerm: z.string().nullish(),
      stage: z.string(),
      outcome: z.string(),
      reason: z.string().nullish(),
      detail: z.record(z.string(), z.unknown()).optional(),
      inReport: z.boolean(),
    }),
  ),
});

/* ── Sources ─────────────────────────────────────────────────── */

export const ingestionRunViewSchema = z.object({
  id: uuid,
  sourceId: z.string(),
  mode: z.enum(['INCREMENTAL', 'BACKFILL', 'ON_DEMAND']).optional(),
  status: z.enum(['RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED']),
  documentsFetched: z.number().int().optional(),
  documentsCreated: z.number().int().optional(),
  documentsDuplicate: z.number().int().optional(),
  documentsRejected: z.number().int().optional(),
  errorCode: z.string().nullish(),
  errorMessage: z.string().nullish(),
  startedAt: isoDateTime,
  finishedAt: isoDateTime.nullish(),
});

export const sourceViewSchema = z.object({
  id: z.string(),
  displayName: z.string(),
  sourceClass: sourceClassSchema,
  enabled: z.boolean(),
  requiresApiKey: z.boolean().optional(),
  apiKeyConfigured: z.boolean().optional(),
  rateLimitPerMinute: z.number().int().optional(),
  documentCount: z.number().optional(),
  lastRun: ingestionRunViewSchema.optional(),
});

export const sourceViewListSchema = z.array(sourceViewSchema);

export const openAlexQuotaSchema = z.object({
  remainingCredits: z.number().int().nonnegative(),
  limitCredits: z.number().int().nonnegative(),
  keysConfigured: z.number().int().nonnegative(),
  keysChecked: z.number().int().nonnegative(),
  resetsAt: isoDateTime.nullish(),
  checkedAt: isoDateTime,
});

export const ingestionRunPageSchema = z.object({
  ...pageMetaShape,
  content: z.array(ingestionRunViewSchema),
});

/* ── Compile-time contract conformance ───────────────────────────
   `Assert<false>` is a type error, so any schema whose parsed output drifts
   from the generated contract type fails `npm run typecheck` by name. */

type Assert<Ok extends true> = Ok;
type Matches<Parsed, Contract> = [Parsed] extends [Contract] ? true : false;

export type ContractConformance = [
  Assert<Matches<z.infer<typeof analysisParametersSchema>, T.AnalysisParameters>>,
  Assert<Matches<z.infer<typeof analysisProgressSchema>, T.AnalysisProgress>>,
  Assert<Matches<z.infer<typeof researchRequestViewSchema>, T.ResearchRequestView>>,
  Assert<Matches<z.infer<typeof researchRequestPageSchema>, T.ResearchRequestPage>>,
  Assert<Matches<z.infer<typeof indicatorScoreSchema>, T.IndicatorScore>>,
  Assert<Matches<z.infer<typeof emergenceAssessmentSchema>, T.EmergenceAssessment>>,
  Assert<Matches<z.infer<typeof timelinePointSchema>, T.TimelinePoint>>,
  Assert<Matches<z.infer<typeof evidenceSchema>, T.Evidence>>,
  Assert<Matches<z.infer<typeof motivationSchema>, T.Motivation>>,
  Assert<Matches<z.infer<typeof caseExampleSchema>, T.CaseExample>>,
  Assert<Matches<z.infer<typeof rankedTrendSchema>, T.RankedTrend>>,
  Assert<Matches<z.infer<typeof coverageSchema>, T.Coverage>>,
  Assert<Matches<z.infer<typeof trendReportSchema>, T.TrendReport>>,
  Assert<Matches<z.infer<typeof trendFeedbackViewSchema>, T.TrendFeedbackView>>,
  Assert<Matches<z.infer<typeof savedDomainSchema>, T.SavedDomain>>,
  Assert<Matches<z.infer<typeof directionPortraitSchema>, T.DirectionPortrait>>,
  Assert<Matches<z.infer<typeof featureFlagSchema>, T.FeatureFlag>>,
  Assert<Matches<z.infer<typeof radarRefreshSchema>, T.RadarRefresh>>,
  Assert<Matches<z.infer<typeof radarDigestSchema>, T.RadarDigest>>,
  Assert<Matches<z.infer<typeof directionOverlapSchema>, T.DirectionOverlap>>,
  Assert<Matches<z.infer<typeof topicSearchResultSchema>, T.TopicSearchResult>>,
  Assert<Matches<z.infer<typeof foundTopicSchema>, T.FoundTopic>>,
  Assert<Matches<z.infer<typeof quotaSchema>, T.Quota>>,
  Assert<Matches<z.infer<typeof reportDeltaSchema>, T.ReportDelta>>,
  Assert<Matches<z.infer<typeof termExplanationSchema>, T.TermExplanation>>,
  Assert<Matches<z.infer<typeof termExplanationPendingSchema>, T.TermExplanationPending>>,
  Assert<Matches<z.infer<typeof sourceViewSchema>, T.SourceView>>,
  Assert<Matches<z.infer<typeof openAlexQuotaSchema>, T.OpenAlexQuota>>,
  Assert<Matches<z.infer<typeof ingestionRunViewSchema>, T.IngestionRunView>>,
  Assert<Matches<z.infer<typeof ingestionRunPageSchema>, T.IngestionRunPage>>,
];

/**
 * Формулировка направления, которой перекрёстный словарь не знает.
 *
 * Очередь пополнения словаря: чего ему не хватает на самом деле, а не по догадке автора.
 */
export const unrecognizedDirectionSchema = z.object({
  query: z.string(),
  normalizedQuery: z.string(),
  occurrences: z.number().int(),
  suggestions: z.array(z.string()).optional(),
  hadAWayOut: z.boolean(),
  firstSeen: z.string(),
  lastSeen: z.string(),
});

export const unrecognizedDirectionsSchema = z.array(unrecognizedDirectionSchema);
