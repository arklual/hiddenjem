/**
 * Новый анализ — «Что ищем?».
 *
 * Ошибку ловим до запуска, а не через пятнадцать минут в готовом отчёте. Направление проверяется
 * движком (`/directions/resolve`) ещё в поле ввода; если по нему уже есть готовый отчёт, экран
 * предлагает открыть его — это мгновенно и не тратит квоту, — а новый запуск становится
 * второстепенным «Всё равно пересчитать».
 *
 * Нераспознанное направление запуск не запрещает: словарь неполон по определению, и продукт обязан
 * принимать вопросы, которых он ещё не знает. Но кнопка становится второстепенной, а рядом —
 * «возможно, вы имели в виду», чтобы случайный отчёт был осознанным выбором, а не сюрпризом.
 *
 * Параметры и режим читаются из адреса — так работают «повторить с поправкой» со страницы отказа и
 * «запустить с мейнстримными темами» из вкладки отсеянного. `Idempotency-Key` выводится из содержимого
 * запроса: двойной щелчок не тратит второй слот квоты.
 */
import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api/client';
import {
  latestReports,
  readyReportFor,
  useKnownDirections,
  useQuota,
  useRecentRequests,
  useReport,
} from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import {
  SOURCE_CLASSES,
  type AnalysisMode,
  type AnalysisParameters,
  type SourceClass,
  type SubmitResearchRequest,
} from '@/api/types';
import { useToast } from '@/components/toast/ToastContext';
import { RunBanner } from '@/features/radar/RunBanner';
import { directionTitle, normalizeQuery } from '@/features/report/topicModel';
import { fieldErrorMap } from '@/lib/errors';
import { formatCount, plural, pluralize } from '@/lib/format';
import { translate } from '@/lib/i18n/translate';
import { RUNS_KEY, readLocal, writeLocal, type TrackedRun } from '@/lib/localState';
import { randomUuid } from '@/lib/uuid';
import { RUNS, TOPICS, shortDate } from '@/lib/words';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { HjIcon } from '@/ui/HjIcon';

const QUERY_MIN = 3;
const QUERY_MAX = 200;
const MODES: readonly AnalysisMode[] = ['fast', 'quality'];
const DEFAULTS = {
  topN: 15,
  yearsWindow: 7,
  minConfidence: 0,
  includeMature: false,
  mode: 'fast' as AnalysisMode,
};
const CONFIDENCE_OPTIONS: ReadonlyArray<[number, string]> = [
  [0, 'Любая'],
  [0.4, 'От 40 %'],
  [0.5, 'От 50 %'],
  [0.75, 'От 75 %'],
];

interface ComboOption {
  id: string;
  query: string;
  sub: string;
  ready: boolean;
}

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

function numberParam(params: URLSearchParams, name: string, fallback: number): number {
  const raw = params.get(name);
  const value = Number(raw);
  return raw !== null && Number.isFinite(value) ? value : fallback;
}

export function NewAnalysisPage(): React.ReactElement {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();
  const listId = useId();

  const [query, setQuery] = useState(() => params.get('query') ?? params.get('q') ?? '');
  const [checked, setChecked] = useState(() =>
    (params.get('query') ?? params.get('q') ?? '').trim(),
  );
  const [listOpen, setListOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [touched, setTouched] = useState(false);
  const [mode, setMode] = useState<AnalysisMode>(() => {
    const raw = params.get('mode');
    return MODES.includes(raw as AnalysisMode) ? (raw as AnalysisMode) : DEFAULTS.mode;
  });
  const [topN, setTopN] = useState(() => String(numberParam(params, 'topN', DEFAULTS.topN)));
  const [yearsWindow, setYearsWindow] = useState(() =>
    String(numberParam(params, 'yearsWindow', DEFAULTS.yearsWindow)),
  );
  const [minConfidence, setMinConfidence] = useState(() =>
    numberParam(params, 'minConfidence', DEFAULTS.minConfidence),
  );
  const [includeMature, setIncludeMature] = useState(() => {
    const raw = params.get('includeMature') ?? params.get('mature');
    return raw === null ? DEFAULTS.includeMature : raw === '1' || raw === 'true';
  });
  const [sourceClasses, setSourceClasses] = useState<SourceClass[]>(() =>
    params
      .getAll('sourceClasses')
      .filter((value): value is SourceClass =>
        (SOURCE_CLASSES as readonly string[]).includes(value),
      ),
  );
  const [paramsOpen, setParamsOpen] = useState(
    () => params.has('includeMature') || params.has('mature') || params.has('topN'),
  );
  const idempotency = useRef<{ signature: string; key: string } | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const requests = useRecentRequests();
  const known = useKnownDirections();
  const quota = useQuota();
  const ready = latestReports(requests.data?.content ?? []);

  const trimmed = query.trim();
  const debouncedCheck = useDebounced(checked, 150);
  const resolution = useQuery({
    queryKey: queryKeys.directionResolution(normalizeQuery(debouncedCheck)),
    queryFn: ({ signal }) => api.resolveDirection(debouncedCheck, signal),
    enabled: debouncedCheck.length >= QUERY_MIN,
    staleTime: 5 * 60_000,
  });
  const readyRequest =
    checked.length >= QUERY_MIN ? readyReportFor(requests.data?.content, checked) : undefined;
  const readyReport = useReport(readyRequest?.reportId ?? undefined);

  const options = useMemo((): Array<[string, ComboOption[]]> => {
    const needle = normalizeQuery(query);
    const matches = (text: string): boolean => !needle || normalizeQuery(text).includes(needle);
    const readyOptions = ready
      .filter((request) => matches(request.query))
      .slice(0, 6)
      .map((request) => ({
        id: `r-${request.id}`,
        query: request.query,
        sub: `готовый отчёт от ${shortDate(request.finishedAt ?? request.submittedAt)}`,
        ready: true,
      }));
    const readyKeys = new Set(readyOptions.map((option) => normalizeQuery(option.query)));
    const lexicon = (known.data ?? [])
      .filter((term) => needle.length >= 2 && matches(term) && !readyKeys.has(normalizeQuery(term)))
      .slice(0, 6)
      .map((term) => ({
        id: `k-${term}`,
        query: term,
        sub: 'распознаётся словарём направлений',
        ready: false,
      }));
    return [
      ['Направления с готовым отчётом', readyOptions],
      ['Словарь направлений', lexicon],
    ];
  }, [query, ready, known.data]);
  const flat = options.flatMap(([, items]) => items);

  const parameters: AnalysisParameters = {
    topN: Number(topN),
    yearsWindow: Number(yearsWindow),
    minConfidence,
    includeMature,
    sourceClasses,
    mode,
  };
  const topNValid =
    Number.isInteger(Number(topN)) && Number(topN) >= 5 && Number(topN) <= 50 && topN !== '';
  const yearsValid =
    Number.isInteger(Number(yearsWindow)) &&
    Number(yearsWindow) >= 3 &&
    Number(yearsWindow) <= 15 &&
    yearsWindow !== '';

  const submit = useMutation({
    mutationFn: (payload: SubmitResearchRequest) => {
      const signature = JSON.stringify(payload);
      if (idempotency.current?.signature !== signature) {
        idempotency.current = { signature, key: randomUuid() };
      }
      return api.submitResearchRequest(payload, idempotency.current.key);
    },
    onSuccess: (view) => {
      idempotency.current = null;
      void queryClient.invalidateQueries({ queryKey: queryKeys.quota });
      void queryClient.invalidateQueries({ queryKey: ['research-requests'] });
      queryClient.setQueryData(queryKeys.researchRequest(view.id), view);
      if (view.status === 'COMPLETED' && view.reportId) {
        // Сервер отдал готовый результат: ходить через экран хода анализа незачем.
        if (view.fromCache) {
          toast.show('Открыт готовый отчёт', {
            description: 'Такой же вопрос уже считался — квота не потрачена.',
          });
        }
        navigate(`/reports/${view.reportId}`);
        return;
      }
      const runs = readLocal<TrackedRun[]>(RUNS_KEY, []);
      writeLocal(RUNS_KEY, [
        ...runs.filter((run) => run.requestId !== view.id),
        { requestId: view.id, query: view.query, startedAt: new Date().toISOString() },
      ]);
      navigate(`/runs/${view.id}`);
    },
  });

  const pick = (value: string): void => {
    setQuery(value);
    setChecked(value.trim());
    setListOpen(false);
    setActive(-1);
    input.current?.focus();
  };

  const tooShort = trimmed.length > 0 && trimmed.length < QUERY_MIN;
  const fieldError = fieldErrorMap(submit.error).query;
  const inputError =
    touched && trimmed.length === 0
      ? 'Введите направление — хотя бы 3 символа.'
      : touched && tooShort
        ? 'Слишком коротко: нужно хотя бы 3 символа.'
        : (fieldError ?? null);

  const recognized = resolution.data?.recognized;
  const checking =
    checked.length >= QUERY_MIN && (resolution.isFetching || checked !== debouncedCheck);
  const verdictFor = checked === trimmed && trimmed.length >= QUERY_MIN;
  const secondary = verdictFor && readyRequest !== undefined;

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    setListOpen(false);
    setTouched(true);
    setChecked(trimmed);
    if (trimmed.length < QUERY_MIN) {
      input.current?.focus();
      return;
    }
    if (!topNValid || !yearsValid) {
      setParamsOpen(true);
      return;
    }
    submit.mutate({
      query: trimmed,
      parameters,
      // «Всё равно пересчитать» — единственный случай, когда готовый ответ не переиспользуется.
      ...(readyRequest ? { refresh: true } : {}),
    });
  };

  const quotaLine = ((): string | null => {
    const data = quota.data;
    if (!data?.known || data.remaining === undefined) return null;
    if (data.remaining <= 0)
      return 'Запуски на этот час закончились. Готовые отчёты открываются без ограничений.';
    const limit = data.userLimit ? ` из ${data.userLimit}` : '';
    return `Осталось ${pluralize(data.remaining, RUNS)}${limit} в этот час. Готовые отчёты открываются без ограничений.`;
  })();

  const summary = [
    pluralize(Number(topN) || DEFAULTS.topN, TOPICS),
    `окно ${pluralize(Number(yearsWindow) || DEFAULTS.yearsWindow, { one: 'год', few: 'года', many: 'лет' })}`,
    sourceClasses.length === 0
      ? 'все классы источников'
      : `${sourceClasses.length} из ${SOURCE_CLASSES.length} классов источников`,
    includeMature ? 'с мейнстримными темами' : 'без мейнстримных тем',
    minConfidence > 0 ? `уверенность от ${Math.round(minConfidence * 100)} %` : null,
  ]
    .filter(Boolean)
    .join(' · ');

  let optionIndex = -1;

  return (
    <>
      <RunBanner />
      <div className="new-layout">
        <div>
          <div className="od-stack mb-8" style={{ '--od-gap': '12px' } as React.CSSProperties}>
            <h1 className="h1 h1--hero">Что ищем?</h1>
            <p className="lead">
              Назовите технологическое направление — Hiddenjem соберёт публикации, патенты, код и
              отраслевые источники и покажет темы, которые только набирают силу.
            </p>
          </div>

          <form className="new-form" onSubmit={handleSubmit} noValidate>
            <div className="field">
              <label className="label-lg" htmlFor="dir-input">
                Технологическое направление
              </label>
              <div className="combo">
                <span className="combo__icon">
                  <HjIcon name="search" size={22} />
                </span>
                <input
                  ref={input}
                  className="input input--xl"
                  id="dir-input"
                  type="text"
                  role="combobox"
                  aria-autocomplete="list"
                  aria-expanded={listOpen && flat.length > 0}
                  aria-controls={listId}
                  aria-activedescendant={listOpen && flat[active] ? `opt-${active}` : undefined}
                  aria-describedby="dir-error dir-help"
                  aria-required="true"
                  aria-invalid={inputError ? true : undefined}
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={QUERY_MAX}
                  placeholder="Например, защита искусственного интеллекта"
                  value={query}
                  onChange={(event) => {
                    setQuery(event.target.value);
                    setListOpen(true);
                    setActive(-1);
                  }}
                  onFocus={() => setListOpen(true)}
                  onBlur={() => {
                    setListOpen(false);
                    if (trimmed) setTouched(true);
                    setChecked(trimmed);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'ArrowDown') {
                      event.preventDefault();
                      setListOpen(true);
                      setActive((value) => Math.min(value + 1, flat.length - 1));
                    } else if (event.key === 'ArrowUp') {
                      event.preventDefault();
                      setActive((value) => Math.max(value - 1, 0));
                    } else if (event.key === 'Enter' && listOpen && flat[active]) {
                      event.preventDefault();
                      pick(flat[active]?.query ?? '');
                    } else if (event.key === 'Escape' && listOpen) {
                      event.preventDefault();
                      event.stopPropagation();
                      setListOpen(false);
                    }
                  }}
                />
                <ul
                  className="combo__list"
                  id={listId}
                  role="listbox"
                  aria-label="Направления"
                  hidden={!listOpen || flat.length === 0}
                >
                  {options.map(([label, items]) =>
                    items.length === 0 ? null : (
                      <li key={label} role="presentation">
                        <div className="combo__group" role="presentation">
                          {label}
                        </div>
                        <ul role="group" aria-label={label}>
                          {items.map((option) => {
                            optionIndex += 1;
                            const position = optionIndex;
                            // Клавиатура выбирает стрелками и Enter в самом поле (aria-activedescendant).
                            return (
                              // eslint-disable-next-line jsx-a11y/click-events-have-key-events
                              <li
                                key={option.id}
                                id={`opt-${position}`}
                                role="option"
                                className="combo__opt"
                                aria-selected={position === active}
                                onMouseDown={(event) => event.preventDefault()}
                                onClick={() => pick(option.query)}
                              >
                                <span
                                  className="od-stack"
                                  style={{ '--od-gap': '0px' } as React.CSSProperties}
                                >
                                  <span className="t">{option.query}</span>
                                  <span className="s">{option.sub}</span>
                                </span>
                                <HjIcon name={option.ready ? 'file' : 'book'} size={18} />
                              </li>
                            );
                          })}
                        </ul>
                      </li>
                    ),
                  )}
                </ul>
              </div>
              <div id="dir-error">
                {inputError ? (
                  <p className="field-error">
                    <HjIcon name="alert" size={16} />
                    <span>{inputError}</span>
                  </p>
                ) : null}
              </div>
              <p className="help" id="dir-help">
                Пишите направление, а не отдельную технологию: «финтех», а не «токенизированные
                депозиты».
              </p>
              <div aria-live="polite">
                {verdictFor && checking && !readyRequest ? (
                  <p className="help">Проверяем формулировку…</p>
                ) : null}
                {verdictFor && readyRequest ? (
                  <div className="status-box">
                    <p className="status-box__head">
                      <HjIcon name="check" />
                      <span>
                        {recognized === false
                          ? `По «${checked}» уже есть готовый отчёт`
                          : `Направление распознано: «${checked}»`}
                      </span>
                    </p>
                    <p className="help">
                      Отчёт уже готов:{' '}
                      {shortDate(readyRequest.finishedAt ?? readyRequest.submittedAt)},{' '}
                      {readyRequest.parameters.mode === 'quality' ? 'качественный' : 'быстрый'}{' '}
                      режим
                      {readyReport.data
                        ? `, ${pluralize(readyReport.data.trends.length, TOPICS)} по ${formatCount(
                            readyReport.data.coverage.documentsAnalyzed,
                          )} ${plural(readyReport.data.coverage.documentsAnalyzed, {
                            one: 'документу',
                            few: 'документам',
                            many: 'документам',
                          })}`
                        : ''}
                      . Откроется сразу и не спишет запуск из квоты.
                    </p>
                    <div className="actions">
                      <Link
                        className="btn btn--primary"
                        to={`/reports/${readyRequest.reportId ?? ''}`}
                      >
                        Открыть готовый отчёт
                        <HjIcon name="arrow-right" size={18} />
                      </Link>
                    </div>
                  </div>
                ) : null}
                {verdictFor && !readyRequest && !checking && recognized !== undefined ? (
                  <div className="status-box">
                    <p className="status-box__head">
                      <HjIcon name="check" />
                      <span>
                        {recognized
                          ? `Направление распознано: «${checked}»`
                          : `Направление «${checked}»`}
                      </span>
                    </p>
                    <p className="help">Готового отчёта по нему нет — запустите анализ.</p>
                  </div>
                ) : null}
              </div>
            </div>

            <fieldset>
              <legend className="label-lg">Режим анализа</legend>
              <div className="modes">
                {MODES.map((name) => (
                  <label key={name} className="mode-card">
                    <input
                      type="radio"
                      name="mode"
                      value={name}
                      checked={mode === name}
                      onChange={() => setMode(name)}
                    />
                    <span className="mode-card__top">
                      <span className="mode-card__title">{translate(`mode.${name}`)}</span>
                      <span className="mode-card__check">
                        <HjIcon name="check" size={16} />
                      </span>
                    </span>
                    <span className="mode-card__time">{translate(`modeDuration.${name}`)}</span>
                    <span className="mode-card__hint">
                      {name === 'fast'
                        ? 'Глубокое исследование получает 5,5 минуты.'
                        : 'Глубокое исследование читает втрое больше страниц.'}
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>

            <details
              className="params"
              open={paramsOpen}
              onToggle={(event) => setParamsOpen(event.currentTarget.open)}
            >
              <summary>
                <HjIcon name="sliders" />
                <span className="od-stack" style={{ '--od-gap': '2px' } as React.CSSProperties}>
                  <span className="label-lg">Параметры отчёта</span>
                  <span className="caption">{summary}</span>
                </span>
                <HjIcon name="chevron-down" className="chev" />
              </summary>
              <div className="params__body">
                <div className="params__row">
                  <div className="field">
                    <label htmlFor="p-topn">Тем в отчёте</label>
                    <input
                      className="input num"
                      id="p-topn"
                      type="number"
                      min={5}
                      max={50}
                      inputMode="numeric"
                      value={topN}
                      aria-invalid={!topNValid}
                      aria-describedby="p-topn-h"
                      onChange={(event) => setTopN(event.target.value)}
                    />
                    <p className={topNValid ? 'help' : 'help field-error'} id="p-topn-h">
                      {topNValid ? 'от 5 до 50' : 'Нужно целое число от 5 до 50'}
                    </p>
                  </div>
                  <div className="field">
                    <label htmlFor="p-years">Окно, лет</label>
                    <input
                      className="input num"
                      id="p-years"
                      type="number"
                      min={3}
                      max={15}
                      inputMode="numeric"
                      value={yearsWindow}
                      aria-invalid={!yearsValid}
                      aria-describedby="p-years-h"
                      onChange={(event) => setYearsWindow(event.target.value)}
                    />
                    <p className={yearsValid ? 'help' : 'help field-error'} id="p-years-h">
                      {yearsValid ? 'от 3 до 15' : 'Нужно целое число от 3 до 15'}
                    </p>
                  </div>
                  <div className="field">
                    <label htmlFor="p-conf">Минимальная уверенность</label>
                    <select
                      className="select"
                      id="p-conf"
                      value={String(minConfidence)}
                      onChange={(event) => setMinConfidence(Number(event.target.value))}
                    >
                      {CONFIDENCE_OPTIONS.map(([value, label]) => (
                        <option key={value} value={String(value)}>
                          {label}
                        </option>
                      ))}
                    </select>
                    <p className="help">Темы ниже порога не показываются</p>
                  </div>
                </div>
                <fieldset>
                  <legend className="section-label">Классы источников</legend>
                  <div className="od-cluster">
                    {SOURCE_CLASSES.map((sourceClass) => {
                      const on = sourceClasses.length === 0 || sourceClasses.includes(sourceClass);
                      return (
                        <button
                          key={sourceClass}
                          type="button"
                          className="chip"
                          role="checkbox"
                          aria-checked={on}
                          onClick={() => {
                            const current =
                              sourceClasses.length === 0 ? [...SOURCE_CLASSES] : sourceClasses;
                            const next = on
                              ? current.filter((item) => item !== sourceClass)
                              : [...current, sourceClass];
                            if (next.length === 0) {
                              toast.show('Нужен хотя бы один класс источников');
                              return;
                            }
                            setSourceClasses(next.length === SOURCE_CLASSES.length ? [] : next);
                          }}
                        >
                          {translate(`sourceClass.${sourceClass}`)}
                        </button>
                      );
                    })}
                  </div>
                  <p className="help mt-2">По умолчанию включены все.</p>
                </fieldset>
                <div className="switch-row">
                  <div className="od-field">
                    <span className="label-lg" id="p-mature-l">
                      Включать мейнстримные темы
                    </span>
                    <span className="help" id="p-mature-h">
                      Темы, которые уже занимают заметную долю литературы направления, отсеиваются
                      намеренно: Hiddenjem ищет то, что только набирает силу.
                    </span>
                  </div>
                  <button
                    type="button"
                    className="switch"
                    role="switch"
                    aria-checked={includeMature}
                    aria-labelledby="p-mature-l"
                    aria-describedby="p-mature-h"
                    onClick={() => setIncludeMature((value) => !value)}
                  />
                </div>
              </div>
            </details>

            {submit.error ? <ErrorPanel error={submit.error} title="Анализ не запущен" /> : null}

            <div className="form-foot">
              <button
                className={`btn btn--lg ${secondary ? 'btn--secondary' : 'btn--primary'}`}
                type="submit"
                disabled={
                  submit.isPending ||
                  (quota.data?.known === true && (quota.data.remaining ?? 1) <= 0)
                }
              >
                {submit.isPending ? (
                  <>
                    <span className="spinner" aria-hidden="true" />
                    Отправляем…
                  </>
                ) : readyRequest && verdictFor ? (
                  <>
                    <HjIcon name="refresh" />
                    Всё равно пересчитать
                  </>
                ) : (
                  <>
                    Запустить анализ
                    <HjIcon name="arrow-right" />
                  </>
                )}
              </button>
              {quotaLine ? <p className="caption">{quotaLine}</p> : null}
            </div>
          </form>
        </div>

        <aside className="new-aside" aria-label="Подсказки">
          <section className="panel">
            <h2 className="h4">Как это работает</h2>
            <ol className="steps">
              <li>
                <div>
                  <strong>Назовите направление</strong>
                  <span>
                    Подскажем распознанные формулировки и предупредим, если вопрос не поймём, — до
                    запуска, а не через 15 минут.
                  </span>
                </div>
              </li>
              <li>
                <div>
                  <strong>Подождите 12–20 минут</strong>
                  <span>
                    Сбор идёт по живым источникам. Ждать на странице не нужно — покажем уведомление.
                  </span>
                </div>
              </li>
              <li>
                <div>
                  <strong>Получите темы с объяснением</strong>
                  <span>У каждой — балл, стадия, надёжность и источники с цитатами.</span>
                </div>
              </li>
            </ol>
            <p className="caption mt-4">
              Мейнстрим отсеивается намеренно: если знакомой темы нет, загляните во вкладку «Что не
              попало».
            </p>
          </section>
          {ready.length > 0 ? (
            <section>
              <h2 className="h4">Готовые отчёты</h2>
              <ul className="recent">
                {ready.slice(0, 4).map((request) => (
                  <li key={request.id}>
                    <Link to={`/reports/${request.reportId ?? ''}`}>
                      <span>
                        <span className="t">{directionTitle(request.query)}</span>
                        <span className="s">
                          {shortDate(request.finishedAt ?? request.submittedAt)} ·{' '}
                          {request.parameters.mode === 'quality' ? 'качественный' : 'быстрый'} режим
                        </span>
                      </span>
                      <HjIcon name="chevron-right" size={18} />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </aside>
      </div>
    </>
  );
}
