/**
 * «Писали ли мы про это?» — поиск по темам во всех отчётах организации (⌘K, Ctrl+K или /).
 *
 * Темы ищет сервер (`GET /topics/search`) — в границах видимости смотрящего; направления — среди
 * последних отчётов. Пустое поле показывает недавно открытые темы и направления: чаще всего ищут
 * то, что уже открывали.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { latestReports, useRecentRequests } from '@/api/queries';
import { queryKeys } from '@/api/queryKeys';
import { directionTitle, normalizeQuery } from '@/features/report/topicModel';
import { formatDate } from '@/lib/format';
import { RECENT_KEY, useLocal, type TopicSnapshot } from '@/lib/localState';
import { useFeature } from '@/lib/features';
import { HjIcon } from '@/ui/HjIcon';

interface Option {
  id: string;
  kind: 'topic' | 'direction';
  title: string;
  sub: string;
  href: string;
  english: boolean;
}

function Marked({ text, query }: { text: string; query: string }): React.ReactElement {
  const needle = query.trim().toLowerCase();
  const at = needle ? text.toLowerCase().indexOf(needle) : -1;
  if (at < 0) return <>{text}</>;
  return (
    <>
      {text.slice(0, at)}
      <mark>{text.slice(at, at + needle.length)}</mark>
      {text.slice(at + needle.length)}
    </>
  );
}

function useDebounced(value: string, ms: number): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

export function CommandPalette({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}): React.ReactElement {
  const ref = useRef<HTMLDialogElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const debounced = useDebounced(query.trim(), 250);
  const [recent] = useLocal<TopicSnapshot[]>(RECENT_KEY, []);
  const requests = useRecentRequests();

  const topicSearch = useFeature('topic-search');
  const search = useQuery({
    queryKey: queryKeys.topicSearch(debounced),
    queryFn: ({ signal }) => api.searchTopics(debounced, signal),
    // Поиск тем выключается реестром фич: палитра тогда ищет только среди направлений.
    enabled: topicSearch && open && debounced.length >= 2,
    staleTime: 60_000,
  });

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      setQuery('');
      setActive(0);
      dialog.showModal();
      input.current?.focus();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  const directions = useMemo(() => latestReports(requests.data?.content ?? []), [requests.data]);

  const groups = useMemo((): Array<[string, Option[]]> => {
    const needle = normalizeQuery(query);
    const directionOptions = directions
      .filter((request) => !needle || normalizeQuery(request.query).includes(needle))
      .slice(0, needle ? 4 : 6)
      .map((request): Option => ({
        id: `d-${request.id}`,
        kind: 'direction',
        title: directionTitle(request.query),
        sub: `Направление · отчёт от ${formatDate(request.finishedAt ?? request.submittedAt)}`,
        href: `/reports/${request.reportId ?? ''}`,
        english: false,
      }));
    if (!needle) {
      const recentOptions = recent.map((item): Option => ({
        id: `r-${item.reportId}-${item.trendKey}`,
        kind: 'topic',
        title: item.title,
        sub: `${directionTitle(item.query)} · место ${item.rank}${item.original !== item.title ? ` · ${item.original}` : ''}`,
        href: `/reports/${item.reportId}/trends/${encodeURIComponent(item.trendKey)}`,
        english: item.original === item.title,
      }));
      return [
        ['Недавно открытые темы', recentOptions],
        ['Направления', directionOptions],
      ];
    }
    const topicOptions = (search.data?.topics ?? []).slice(0, 8).flatMap((topic): Option[] => {
      const best = [...topic.occurrences].sort((a, b) => a.rank - b.rank)[0];
      if (!best) return [];
      const more = topic.directions > 1 ? ` · ещё в ${topic.directions - 1} напр.` : '';
      return [
        {
          id: `t-${topic.trendKey}`,
          kind: 'topic',
          title: topic.title,
          sub: `${directionTitle(best.query)} · место ${best.rank} · ${formatDate(best.generatedAt)}${more}`,
          href: `/reports/${best.reportId}/trends/${encodeURIComponent(topic.trendKey)}`,
          english: true,
        },
      ];
    });
    return [
      ['Темы', topicOptions],
      ['Направления', directionOptions],
    ];
  }, [query, directions, recent, search.data]);

  const flat = groups.flatMap(([, items]) => items);
  const go = (option: Option | undefined): void => {
    if (!option) return;
    onClose();
    navigate(option.href);
  };

  const searching = debounced.length >= 2 && search.isFetching;
  const waitingForInput = query.trim().length > 0 && query.trim() !== debounced;
  let index = -1;

  return (
    // Щелчок по подложке дублирует Esc, который нативный диалог обрабатывает сам.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions
    <dialog
      ref={ref}
      className="palette"
      aria-label="Поиск по темам во всех отчётах"
      onClose={onClose}
      onClick={(event) => {
        if (event.target === ref.current) onClose();
      }}
    >
      <div className="palette__search">
        <HjIcon name="search" />
        <input
          ref={input}
          type="text"
          role="combobox"
          aria-expanded="true"
          aria-controls="palette-list"
          aria-autocomplete="list"
          aria-activedescendant={flat[active] ? `pal-${active}` : undefined}
          autoComplete="off"
          placeholder="Тема, направление или отчёт"
          aria-label="Что ищем"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setActive(0);
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown') {
              event.preventDefault();
              setActive((value) => Math.min(value + 1, flat.length - 1));
            } else if (event.key === 'ArrowUp') {
              event.preventDefault();
              setActive((value) => Math.max(value - 1, 0));
            } else if (event.key === 'Enter') {
              event.preventDefault();
              go(flat[active]);
            }
          }}
        />
        <kbd>Esc</kbd>
      </div>
      <div id="palette-list" className="palette__list" role="listbox" aria-label="Результаты">
        {groups.map(([label, items]) =>
          items.length === 0 ? null : (
            <div key={label} role="group" aria-label={label}>
              <div className="palette__group" role="presentation">
                {label}
              </div>
              {items.map((option) => {
                index += 1;
                const position = index;
                // Выбор с клавиатуры — стрелками и Enter в поле поиска (aria-activedescendant);
                // щелчок мышью — второй путь к тому же действию.
                return (
                  // eslint-disable-next-line jsx-a11y/click-events-have-key-events
                  <div
                    key={option.id}
                    tabIndex={-1}
                    id={`pal-${position}`}
                    role="option"
                    className="palette__opt"
                    aria-selected={position === active}
                    onMouseEnter={() => setActive(position)}
                    onClick={() => go(option)}
                  >
                    <HjIcon name={option.kind === 'direction' ? 'layers' : 'file'} />
                    <span>
                      <span className="t" lang={option.english ? 'en' : undefined}>
                        <Marked text={option.title} query={query} />
                      </span>
                      <span className="s">{option.sub}</span>
                    </span>
                    <HjIcon name="arrow-right" size={16} />
                  </div>
                );
              })}
            </div>
          ),
        )}
        {flat.length === 0 ? (
          searching || waitingForInput ? (
            <div className="palette__empty" role="status">
              <p className="caption">Ищем во всех отчётах…</p>
            </div>
          ) : search.isError ? (
            <div className="palette__empty" role="status">
              <p>
                <strong>Поиск сейчас недоступен</strong>
              </p>
              <p className="caption">Сервер не ответил. Попробуйте ещё раз через минуту.</p>
            </div>
          ) : query.trim().length > 0 ? (
            <div className="palette__empty">
              <p>
                <strong>«{query.trim()}» нет ни в одном отчёте</strong>
              </p>
              <p className="caption">
                Мы ещё не писали про это. Запустите анализ направления, в которое входит тема, —
                узкие темы система найдёт сама.
              </p>
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={() => {
                  onClose();
                  navigate('/new');
                }}
              >
                Новый анализ
              </button>
            </div>
          ) : (
            <div className="palette__empty">
              <p className="caption">Начните вводить название темы или направления.</p>
            </div>
          )
        ) : null}
      </div>
      <div className="palette__foot">
        <span>
          <kbd>↑</kbd>
          <kbd>↓</kbd> выбрать
        </span>
        <span>
          <kbd>Enter</kbd> открыть
        </span>
        <span>Ищем по всем отчётам организации</span>
      </div>
    </dialog>
  );
}
