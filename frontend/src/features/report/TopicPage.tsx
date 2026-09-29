/**
 * Карточка темы — страница для комитета, в порядке чтения: суть → почему это зарождающаяся тема →
 * динамика → кто уже делает → источники → насколько этому верить.
 *
 * Сбоку — балл, «В записку», «Отслеживать тему», оценка и оглавление. Между темами листают
 * клавишами [ и ].
 *
 * Всё на странице — из ответа движка: объяснение у каждого индикатора — его собственный текст,
 * организация названа только с основанием, у каждого источника — доверенность и правило, по
 * которому она присвоена. Чего движок не прислал, того здесь нет, и страница так и говорит.
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useReport, useReportDelta } from '@/api/queries';
import type { Evidence, RankedTrend, TrendReport } from '@/api/types';
import { useBriefing } from '@/app/BriefingDialog';
import { countryFlag, formatDate, formatPercent, pluralize } from '@/lib/format';
import { translate } from '@/lib/i18n/translate';
import { safeHref } from '@/lib/links';
import {
  BRIEFING_KEY,
  WATCH_KEY,
  rememberRecent,
  topicId,
  useLocal,
  type TopicSnapshot,
} from '@/lib/localState';
import { DOCUMENTS } from '@/lib/words';
import { useFeature } from '@/lib/features';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { HjIcon } from '@/ui/HjIcon';
import { caseBasisNote } from './caseBasis';
import {
  RangeViz,
  RelTag,
  ScoreBar,
  StageTag,
  TrustBlock,
  VerdictTag,
  YearChart,
} from './TopicBits';
import { buildRows } from './TopicsPanel';
import { FeedbackChips, useFeedback } from './useFeedback';
import {
  DIRECTION_SHARE_FLOOR,
  directionTitle,
  evidenceTitleRu,
  reliabilityOf,
  reportTopN,
  sourceName,
  topicDefinition,
  topicSnapshot,
  topicTitle,
  trendStatement,
  yearSeries,
} from './topicModel';

const SECTIONS: ReadonlyArray<[string, string]> = [
  ['s-essence', 'Суть'],
  ['s-why', 'Почему слабый сигнал'],
  ['s-dyn', 'Динамика'],
  ['s-cases', 'Кто уже делает'],
  ['s-sources', 'Источники'],
  ['s-trust', 'Насколько верить'],
];

function scrollToId(id: string): void {
  const element = document.getElementById(id);
  if (!element) return;
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  element.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
  element.setAttribute('tabindex', '-1');
  element.focus({ preventScroll: true });
  if (element.classList.contains('ev')) {
    element.classList.add('is-target');
    setTimeout(() => element.classList.remove('is-target'), 2400);
  }
}

function Cites({
  trend,
  statement,
}: {
  trend: RankedTrend;
  statement: 'problem' | 'benefit';
}): React.ReactElement | null {
  const refs = [
    ...new Set(
      (trend.motivation.attributions ?? [])
        .filter((item) => item.statement === statement && trend.evidence[item.evidenceIndex])
        .map((item) => item.evidenceIndex + 1),
    ),
  ];
  if (refs.length === 0) return null;
  return (
    <>
      {refs.map((ref) => (
        <button
          key={ref}
          type="button"
          className="cite"
          aria-label={`Источник ${ref}: ${trend.evidence[ref - 1]?.title ?? ''}`}
          onClick={() => scrollToId(`ev-${ref}`)}
        >
          {ref}
        </button>
      ))}
    </>
  );
}

function useCurrentSection(): string | null {
  const [current, setCurrent] = useState<string | null>(null);
  useEffect(() => {
    if (!('IntersectionObserver' in window)) return;
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) if (entry.isIntersecting) setCurrent(entry.target.id);
      },
      { rootMargin: '-25% 0px -65% 0px' },
    );
    SECTIONS.forEach(([id]) => {
      const element = document.getElementById(id);
      if (element) observer.observe(element);
    });
    return () => observer.disconnect();
  });
  return current;
}

const CREDIBILITY_ORDER = ['HIGH', 'MEDIUM', 'LOW'] as const;

function EvidenceItem({
  trend,
  evidence,
  index,
}: {
  trend: RankedTrend;
  evidence: Evidence;
  index: number;
}): React.ReactElement {
  const ru = evidenceTitleRu(trend, index);
  const href = safeHref(evidence.url);
  const credibility = evidence.credibility ?? 'MEDIUM';
  const flag = countryFlag(evidence.organizationCountry);
  return (
    <li className="ev" id={`ev-${index + 1}`} data-class={evidence.sourceClass}>
      <span className="ev__n" aria-hidden="true">
        {index + 1}
      </span>
      <div className="ev__body">
        <p className="ev__meta">
          <span className="tag">
            {translate(`sourceClass.${evidence.sourceClass}` as 'sourceClass.PREPRINT') ||
              evidence.sourceClass}
          </span>
          {evidence.publishedOn ? <span>{formatDate(evidence.publishedOn)}</span> : null}
          {evidence.organization ? (
            <span>
              {flag ? `${flag} ` : ''}
              {evidence.organization}
            </span>
          ) : null}
          {evidence.language ? <span>язык: {evidence.language}</span> : null}
          {evidence.citationCount ? (
            <span>
              {pluralize(evidence.citationCount, {
                one: 'цитирование',
                few: 'цитирования',
                many: 'цитирований',
              })}
            </span>
          ) : null}
        </p>
        <h4 className="ev__title">
          {href ? (
            <a href={href} target="_blank" rel="noopener noreferrer">
              <span className="sr-only">Источник {index + 1}. </span>
              <span lang="en">{evidence.title}</span>
              <HjIcon name="external" size={16} />
              <span className="sr-only"> (откроется в новой вкладке)</span>
            </a>
          ) : (
            <span lang="en">{evidence.title}</span>
          )}
        </h4>
        {ru ? (
          <p className="ev__ru">
            <span className="machine">
              <HjIcon name="info" size={14} />
              {trend.localization?.textMode === 'GENERATIVE'
                ? 'перевод названия языковой моделью'
                : 'машинный перевод названия'}
            </span>
            <span>{ru}</span>
          </p>
        ) : null}
        <p className="ev__authors">
          {evidence.authors ? `${evidence.authors} · ` : ''}
          {sourceName(evidence.sourceId)}
        </p>
        {evidence.snippet ? (
          <blockquote className="ev__quote" lang="en">
            {evidence.snippet}
          </blockquote>
        ) : null}
        <p className="caption">
          Доверенность {translate(`credibility.${credibility}` as 'credibility.HIGH')}:{' '}
          {evidence.credibilityBasis ??
            translate(`credibilityHint.${credibility}` as 'credibilityHint.HIGH')}
          .{evidence.independent === false ? ' Не независимое свидетельство — перепечатка.' : ''}
        </p>
      </div>
    </li>
  );
}

function Body({ report, trend }: { report: TrendReport; trend: RankedTrend }): React.ReactElement {
  const navigate = useNavigate();
  const delta = useReportDelta(report.id, useFeature('report-delta'));
  const onVerdict = useFeedback(report);
  const feedbackOn = useFeature('trend-feedback');
  const { toggleBrief, toggleWatch } = useBriefing();
  const [briefing] = useLocal<TopicSnapshot[]>(BRIEFING_KEY, []);
  const [watched] = useLocal<TopicSnapshot[]>(WATCH_KEY, []);
  const [classFilter, setClassFilter] = useState<string>('');
  const currentSection = useCurrentSection();

  const topN = reportTopN(report);
  const reliability = reliabilityOf(trend, topN);
  const row = buildRows(report, delta.data).find((item) => item.trend.trendKey === trend.trendKey);
  const title = topicTitle(trend);
  const statement = trendStatement(trend);
  const id = `${report.id}|${trend.trendKey}`;
  const inBrief = briefing.some((item) => topicId(item) === id);
  const isWatched = watched.some((item) => topicId(item) === id);
  const series = yearSeries(trend, report.generatedAt);
  const index = report.trends.findIndex((item) => item.trendKey === trend.trendKey);
  const previous = report.trends[index - 1];
  const next = report.trends[index + 1];
  const href = (item: RankedTrend): string =>
    `/reports/${report.id}/trends/${encodeURIComponent(item.trendKey)}`;

  useEffect(() => {
    rememberRecent(topicSnapshot(report, trend));
  }, [report, trend]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (document.querySelector('dialog[open]')) return;
      const target = event.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return;
      if (event.key === ']' && next) navigate(href(next));
      if (event.key === '[' && previous) navigate(href(previous));
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  });

  const problem = trend.localization?.problem?.trim() || trend.motivation.problem.trim();
  const benefit = trend.localization?.benefit?.trim() || trend.motivation.benefit.trim();
  const localized = !!trend.localization;

  const classes = useMemo(() => {
    const counts = new Map<string, number>();
    for (const evidence of trend.evidence)
      counts.set(evidence.sourceClass, (counts.get(evidence.sourceClass) ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]);
  }, [trend]);
  const maxClass = Math.max(1, ...classes.map(([, count]) => count));
  const credibility = Object.fromEntries(
    CREDIBILITY_ORDER.map((level) => [
      level,
      trend.evidence.filter((evidence) => (evidence.credibility ?? 'MEDIUM') === level).length,
    ]),
  ) as Record<(typeof CREDIBILITY_ORDER)[number], number>;
  const caseNote = trend.caseExample ? caseBasisNote(trend.caseExample.basis) : null;
  const share = reliability.share;
  const range = trend.assessment.rankStability;
  const searchQuery = encodeURIComponent(`"${trend.title}"`);

  return (
    <>
      <nav className="crumbs" aria-label="Путь">
        <Link to="/reports">Отчёты</Link>
        <HjIcon name="chevron-right" size={16} />
        <Link to={`/reports/${report.id}`}>{directionTitle(report.query)}</Link>
        <HjIcon name="chevron-right" size={16} />
        <span aria-current="page">Тема {trend.rank}</span>
      </nav>
      <div className="topic-head">
        <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
          <p className="eyebrow">
            Место {trend.rank} из {report.trends.length}
            {row?.isNew
              ? ' · новая с прошлого прогона'
              : row?.previousRank
                ? ` · в прошлом прогоне — ${row.previousRank}`
                : ''}
          </p>
          {statement ? (
            <>
              <h1 className="h1 h1--trend">{statement}</h1>
              <p className="orig">
                <b>{title.title}</b>
                {title.original ? (
                  <>
                    {' · '}
                    <span lang="en">{title.original}</span>
                  </>
                ) : null}
                {trend.localization?.statementModel
                  ? ` · тренд сформулирован моделью ${trend.localization.statementModel} по источникам темы`
                  : ''}
              </p>
            </>
          ) : (
            <h1 className="h1" lang={title.original ? undefined : 'en'}>
              {title.title}
            </h1>
          )}
          {!statement && title.original ? (
            <p className="orig">
              <b lang="en">{title.original}</b> — оригинальное название
              {trend.localization?.titleModel
                ? ` · ${trend.localization.titleMode === 'GENERATIVE' ? 'перевод языковой моделью' : 'перевод'}: ${trend.localization.titleModel}`
                : ''}
            </p>
          ) : null}
          <div className="od-cluster mt-2" style={{ '--od-gap': '16px' } as React.CSSProperties}>
            <StageTag stage={trend.lifecycleStage} />
            <RelTag level={reliability.level} />
            <VerdictTag trend={trend} />
          </div>
        </div>
        <div className="pager">
          {previous ? (
            <Link
              className="icon-btn"
              to={href(previous)}
              aria-label={`Предыдущая тема: ${topicTitle(previous).title}`}
              title="Предыдущая тема ([)"
            >
              <HjIcon name="chevron-left" />
            </Link>
          ) : (
            <span className="icon-btn" aria-disabled="true">
              <HjIcon name="chevron-left" />
            </span>
          )}
          {next ? (
            <Link
              className="icon-btn"
              to={href(next)}
              aria-label={`Следующая тема: ${topicTitle(next).title}`}
              title="Следующая тема (])"
            >
              <HjIcon name="chevron-right" />
            </Link>
          ) : (
            <span className="icon-btn" aria-disabled="true">
              <HjIcon name="chevron-right" />
            </span>
          )}
        </div>
      </div>

      <div className="topic-layout">
        <aside className="topic-aside card" aria-label="Итог и действия">
          <div className="preview__score">
            <span className="big-score num">{Math.round(trend.assessment.score)}</span>
            <span className="big-score-cap">
              <span className="caption">балл зарождения из 100</span>
              <ScoreBar score={trend.assessment.score} />
            </span>
          </div>
          <div className="aside-actions">
            <button
              type="button"
              className="btn btn--primary btn--block"
              aria-pressed={inBrief}
              onClick={() => toggleBrief(topicSnapshot(report, trend))}
            >
              <HjIcon name={inBrief ? 'check' : 'bookmark'} size={18} />
              {inBrief ? 'В записке' : 'Добавить в записку'}
            </button>
            <button
              type="button"
              className="btn btn--secondary btn--block"
              aria-pressed={isWatched}
              onClick={() => toggleWatch(topicSnapshot(report, trend))}
            >
              <HjIcon name={isWatched ? 'check' : 'bell'} size={18} />
              {isWatched ? 'Отслеживается' : 'Отслеживать тему'}
            </button>
          </div>
          {feedbackOn ? <FeedbackChips trend={trend} onVerdict={onVerdict} idPrefix="tp" /> : null}
          <nav className="toc" aria-label="Разделы карточки">
            {SECTIONS.map(([sectionId, label]) => (
              <a
                key={sectionId}
                href={`#${sectionId}`}
                className={currentSection === sectionId ? 'is-current' : undefined}
                aria-current={currentSection === sectionId ? 'location' : undefined}
                onClick={(event) => {
                  event.preventDefault();
                  scrollToId(sectionId);
                }}
              >
                {label}
              </a>
            ))}
          </nav>
        </aside>

        <article className="topic-main" aria-label="Карточка темы">
          <section className="tsec" id="s-essence" aria-labelledby="h-essence">
            <h2 className="h2" id="h-essence">
              Суть
            </h2>
            <dl className="essence">
              <div>
                <dt>Что это</dt>
                <dd>{topicDefinition(trend)}</dd>
              </div>
              <div>
                <dt>Какую проблему решает</dt>
                <dd>
                  {problem ? (
                    <>
                      {problem}
                      <Cites trend={trend} statement="problem" />
                    </>
                  ) : (
                    <span className="body-muted">
                      Не извлечено: в документах темы нет предложения, которое модель признала бы
                      описанием проблемы.
                    </span>
                  )}
                </dd>
              </div>
              <div>
                <dt>Какое преимущество даёт</dt>
                <dd>
                  {benefit ? (
                    <>
                      {benefit}
                      <Cites trend={trend} statement="benefit" />
                    </>
                  ) : (
                    <span className="body-muted">
                      Не извлечено: в документах темы нет утверждения о преимуществе.
                    </span>
                  )}
                </dd>
              </div>
            </dl>
            <p className="machine">
              <HjIcon name="info" size={14} />
              {localized
                ? `Русский текст — ${trend.localization?.textMode === 'GENERATIVE' ? 'перевод языковой моделью' : 'машинный перевод'}${
                    trend.localization?.textModel ? ` (${trend.localization.textModel})` : ''
                  } предложений из источников; оригиналы и цитаты — в разделе «Источники».`
                : 'Русский слой для этого отчёта не построен — показан текст, извлечённый из источников.'}
            </p>
          </section>

          {trend.explanation && trend.explanation.length > 0 ? (
            <section className="tsec" id="s-why" aria-labelledby="h-why">
              <div className="tsec__head">
                <h2 className="h2" id="h-why">
                  Почему это слабый сигнал
                </h2>
                <p className="body-muted prose">
                  Из чего движок заключил, что тема ранняя, и откуда такая уверенность.
                </p>
              </div>
              <dl className="why-list">
                {trend.explanation.map((item) => (
                  <div key={item.title} className="why-item">
                    <dt className="h5">{item.title}</dt>
                    <dd className="body">{item.text}</dd>
                  </div>
                ))}
              </dl>
            </section>
          ) : null}

          <section className="tsec" id="s-dyn" aria-labelledby="h-dyn">
            <div className="tsec__head">
              <h2 className="h2" id="h-dyn">
                Динамика публикаций
              </h2>
              <p className="body-muted">
                {series
                  .slice(-3)
                  .map(
                    (point) =>
                      `${point.year}${point.partial ? ' (неполный)' : ''} — ${point.documents}`,
                  )
                  .join(', ')}
                .
              </p>
            </div>
            <YearChart series={series} firstYear={trend.firstMentionYear} />
          </section>

          <section className="tsec" id="s-cases" aria-labelledby="h-cases">
            <div className="tsec__head">
              <h2 className="h2" id="h-cases">
                Кто уже делает
              </h2>
              <p className="body-muted prose">
                Организацию называем только с основанием — патент, публикация компании или
                академическая группа. Статью не выдаём за внедрение.
              </p>
            </div>
            {trend.caseExample ? (
              <ul className="cases">
                <li className="case">
                  <div className="case__org">
                    <span className="org-mark">
                      <HjIcon name="building" />
                    </span>
                    <span className="od-stack" style={{ '--od-gap': '0px' } as React.CSSProperties}>
                      <strong>{trend.caseExample.organization}</strong>
                      <span>
                        {trend.caseExample.organizationType
                          ? translate(`organizationType.${trend.caseExample.organizationType}`)
                          : 'Организация'}
                        {trend.caseExample.country
                          ? ` · ${countryFlag(trend.caseExample.country) ?? ''} ${trend.caseExample.country}`
                          : ''}
                      </span>
                    </span>
                  </div>
                  {caseNote ? (
                    <p className="case__basis">
                      <b>Основание:</b> {caseNote.label.toLowerCase()} — {caseNote.detail}
                    </p>
                  ) : null}
                  {trend.caseExample.summary ? (
                    <p className="caption" lang="en">
                      {trend.caseExample.summary}
                    </p>
                  ) : null}
                  {trend.evidence[trend.caseExample.evidenceIndex] ? (
                    <div>
                      <button
                        type="button"
                        className="link"
                        onClick={() =>
                          scrollToId(`ev-${(trend.caseExample?.evidenceIndex ?? 0) + 1}`)
                        }
                      >
                        Источник {trend.caseExample.evidenceIndex + 1}
                      </button>
                    </div>
                  ) : null}
                </li>
              </ul>
            ) : (
              <div className="empty">
                <h3>Организация не названа</h3>
                <p className="body-muted">
                  Ни один документ темы не подтверждает, что организация применяет технологию.
                  Авторы и аффилиации — в источниках.
                </p>
              </div>
            )}
          </section>

          <section className="tsec" id="s-sources" aria-labelledby="h-sources">
            <div className="tsec__head">
              <h2 className="h2" id="h-sources">
                Источники
              </h2>
              <p className="body-muted">{pluralize(trend.totalDocuments, DOCUMENTS)} по теме.</p>
            </div>
            {classes.length > 0 ? (
              <ul className="class-bars">
                {classes.map(([sourceClass, count]) => (
                  <li key={sourceClass}>
                    <span>
                      {translate(`sourceClass.${sourceClass}` as 'sourceClass.PREPRINT') ||
                        sourceClass}
                    </span>
                    <span className="ind__track" aria-hidden="true">
                      <i style={{ width: `${(count / maxClass) * 100}%` }} />
                    </span>
                    <span className="num">{count}</span>
                  </li>
                ))}
              </ul>
            ) : null}
            {trend.evidence.length > 0 ? (
              <div className="od-stack" style={{ '--od-gap': '16px' } as React.CSSProperties}>
                <h3 className="h3">Документы · {trend.evidence.length}</h3>
                {classes.length > 1 ? (
                  <div className="ev-filter" role="group" aria-label="Класс источника">
                    <button
                      type="button"
                      className="chip"
                      aria-pressed={classFilter === ''}
                      onClick={() => setClassFilter('')}
                    >
                      Все <span className="n">{trend.evidence.length}</span>
                    </button>
                    {classes.map(([sourceClass, count]) => (
                      <button
                        key={sourceClass}
                        type="button"
                        className="chip"
                        aria-pressed={classFilter === sourceClass}
                        onClick={() => setClassFilter(sourceClass)}
                      >
                        {translate(`sourceClass.${sourceClass}` as 'sourceClass.PREPRINT') ||
                          sourceClass}{' '}
                        <span className="n">{count}</span>
                      </button>
                    ))}
                  </div>
                ) : null}
                <ol className="evidence">
                  {trend.evidence.map((evidence, position) =>
                    classFilter && evidence.sourceClass !== classFilter ? null : (
                      <EvidenceItem
                        key={`${evidence.url}-${position}`}
                        trend={trend}
                        evidence={evidence}
                        index={position}
                      />
                    ),
                  )}
                </ol>
              </div>
            ) : (
              <div className="empty">
                <h3>Список документов не передан</h3>
                <p className="body-muted">Найти их можно прямо в источниках:</p>
                <div className="search-links">
                  <a
                    className="btn btn--secondary btn--sm"
                    href={`https://arxiv.org/search/?query=${searchQuery}&searchtype=all`}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    arXiv
                    <HjIcon name="external" size={16} />
                  </a>
                  <a
                    className="btn btn--secondary btn--sm"
                    href={`https://www.semanticscholar.org/search?q=${searchQuery}`}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Semantic Scholar
                    <HjIcon name="external" size={16} />
                  </a>
                  <a
                    className="btn btn--secondary btn--sm"
                    href={`https://github.com/search?q=${searchQuery}&type=repositories`}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    GitHub
                    <HjIcon name="external" size={16} />
                  </a>
                </div>
              </div>
            )}
          </section>

          <section className="tsec" id="s-trust" aria-labelledby="h-trust">
            <div className="tsec__head">
              <h2 className="h2" id="h-trust">
                Насколько этому верить
              </h2>
            </div>
            <TrustBlock reliability={reliability} />
            <div className="trust-grid">
              {range ? (
                <div className="trust-card trust-card--wide">
                  <h3 className="h4">Место при разных весах</h3>
                  <p className="body-muted">
                    Балл пересчитан при разных наборах весов индикаторов. Место темы — от{' '}
                    {range.best} до {range.worst}.
                    {reliability.stable
                      ? ' Тема остаётся в отчёте при любом наборе.'
                      : ` При части наборов тема выпадает из ТОП-${topN}.`}
                  </p>
                  <RangeViz best={range.best} worst={range.worst} rank={trend.rank} topN={topN} />
                </div>
              ) : null}
              {share !== null ? (
                <div className="trust-card">
                  <h3 className="h4">Доля направления</h3>
                  <p className="v">
                    {formatPercent(share)}
                    <small> документов</small>
                  </p>
                  <span className="share-track" aria-hidden="true">
                    <i style={{ width: `${Math.max(0, Math.min(1, share)) * 100}%` }} />
                  </span>
                  <p className="caption">
                    документов темы источники относят к направлению «{directionTitle(report.query)}
                    ». У настоящих тем направления это обычно около двух третей; ниже{' '}
                    {formatPercent(DIRECTION_SHARE_FLOOR)} — повод проверить.
                  </p>
                </div>
              ) : null}
              <div className="trust-card">
                <h3 className="h4">Доверенность источников</h3>
                <ul className="cred-list">
                  <li>
                    <span>Высокая — рецензируемые публикации, патенты, стандарты</span>
                    <span className="num">{credibility.HIGH}</span>
                  </li>
                  <li>
                    <span>Средняя — препринты, репозитории, аналитика</span>
                    <span className="num">{credibility.MEDIUM}</span>
                  </li>
                  <li>
                    <span>Пониженная — отраслевые медиа, пресс-релизы</span>
                    <span className="num">{credibility.LOW}</span>
                  </li>
                </ul>
                <p className="caption">
                  По{' '}
                  {pluralize(trend.evidence.length, {
                    one: 'документу',
                    few: 'документам',
                    many: 'документам',
                  })}
                  . Уверенность модели в теме: {formatPercent(trend.assessment.confidence)}.
                </p>
              </div>
            </div>
          </section>
        </article>
      </div>

      <nav className="pager-cards" aria-label="Соседние темы">
        {previous ? (
          <Link className="pager-card" to={href(previous)}>
            <span className="caption">
              <HjIcon name="arrow-left" size={16} />
              Место {previous.rank}
            </span>
            <strong lang={topicTitle(previous).original ? undefined : 'en'}>
              {topicTitle(previous).title}
            </strong>
          </Link>
        ) : (
          <span />
        )}
        {next ? (
          <Link className="pager-card pager-card--next" to={href(next)}>
            <span className="caption">
              Место {next.rank}
              <HjIcon name="arrow-right" size={16} />
            </span>
            <strong lang={topicTitle(next).original ? undefined : 'en'}>
              {topicTitle(next).title}
            </strong>
          </Link>
        ) : null}
      </nav>
    </>
  );
}

export function TopicPage(): React.ReactElement {
  const { reportId = '', trendKey = '' } = useParams();
  const report = useReport(reportId);
  const trend = report.data?.trends.find((item) => item.trendKey === trendKey);

  if (report.isLoading) {
    return (
      <p className="caption" role="status">
        Загружаем тему…
      </p>
    );
  }
  if (report.isError || !report.data) {
    return (
      <>
        <nav className="crumbs" aria-label="Путь">
          <Link to="/reports">Отчёты</Link>
        </nav>
        <h1 className="h1 mb-6">Тема не открылась</h1>
        <ErrorPanel error={report.error} onRetry={() => void report.refetch()} />
      </>
    );
  }
  if (!trend) {
    return (
      <>
        <div className="page-head">
          <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
            <h1 className="h1">Такой темы в отчёте нет</h1>
            <p className="lead">Возможно, ссылка ведёт на тему из другой версии отчёта.</p>
          </div>
        </div>
        <Link className="btn btn--primary" to={`/reports/${report.data.id}`}>
          К отчёту «{directionTitle(report.data.query)}»
        </Link>
      </>
    );
  }
  return <Body key={trend.trendKey} report={report.data} trend={trend} />;
}
