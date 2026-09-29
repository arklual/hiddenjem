/**
 * Состояние, которое живёт в браузере аналитика: записка, отслеживаемые темы, недавно открытые
 * темы, запущенные отсюда анализы, закрытая подсказка первого визита.
 *
 * Сервер этого не хранит, и это честно: записка — черновик одного человека до того, как он её
 * отправит, а «отслеживать тему» — закладка, а не подписка с рассылкой. В каждую запись кладётся
 * снимок того, что аналитик видел в момент действия (название, место, направление), — чтобы записка
 * собиралась без повторного похода за отчётами и оставалась верной отчёту, из которого взята.
 *
 * Хранилище недоступно (приватный режим, запрет) — работаем в памяти до перезагрузки.
 */
import { useSyncExternalStore } from 'react';

const PREFIX = 'hj:';

type Listener = () => void;
const listeners = new Map<string, Set<Listener>>();
const cache = new Map<string, unknown>();

function read<T>(key: string, fallback: T): T {
  if (cache.has(key)) return cache.get(key) as T;
  let value: T = fallback;
  try {
    const raw = window.localStorage.getItem(PREFIX + key);
    if (raw !== null) value = JSON.parse(raw) as T;
  } catch {
    value = fallback;
  }
  cache.set(key, value);
  return value;
}

export function writeLocal<T>(key: string, value: T): void {
  cache.set(key, value);
  try {
    window.localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // Хранилище недоступно — значение остаётся в памяти.
  }
  listeners.get(key)?.forEach((listener) => listener());
}

export function readLocal<T>(key: string, fallback: T): T {
  return read(key, fallback);
}

export function useLocal<T>(key: string, fallback: T): [T, (next: T) => void] {
  const value = useSyncExternalStore(
    (listener) => {
      let set = listeners.get(key);
      if (!set) {
        set = new Set();
        listeners.set(key, set);
      }
      set.add(listener);
      return () => set?.delete(listener);
    },
    () => read(key, fallback),
    () => fallback,
  );
  return [value, (next: T) => writeLocal(key, next)];
}

/** Для тестов: забыть закэшированное, чтобы следующий тест читал хранилище заново. */
export function resetLocalCache(): void {
  cache.clear();
}

/* ── Записи ─────────────────────────────────────────────────── */

/** Тема глазами аналитика в момент, когда он её отметил. */
export interface TopicSnapshot {
  reportId: string;
  trendKey: string;
  /** Русское название, если отчёт его нёс, иначе оригинальное. */
  title: string;
  original: string;
  /** Тема как тренд — одно предложение, если отчёт его нёс. */
  statement?: string;
  query: string;
  rank: number;
  score: number;
  stage: string;
  reliability: 'ok' | 'check' | 'low';
  flags: string[];
  definition: string;
  problem?: string;
  benefit?: string;
  sources: { title: string; url: string; organization?: string; publishedOn?: string }[];
  documents: number;
  savedAt: string;
}

export const BRIEFING_KEY = 'briefing';
export const WATCH_KEY = 'watch';
export const RECENT_KEY = 'recent';
export const RUNS_KEY = 'runs';
export const INTRO_KEY = 'introDone';

export const topicId = (item: { reportId: string; trendKey: string }): string =>
  `${item.reportId}|${item.trendKey}`;

export function toggleTopic(key: string, snapshot: TopicSnapshot): boolean {
  const list = readLocal<TopicSnapshot[]>(key, []);
  const id = topicId(snapshot);
  const present = list.some((item) => topicId(item) === id);
  writeLocal(key, present ? list.filter((item) => topicId(item) !== id) : [...list, snapshot]);
  return !present;
}

export function rememberRecent(snapshot: TopicSnapshot): void {
  const list = readLocal<TopicSnapshot[]>(RECENT_KEY, []);
  const id = topicId(snapshot);
  writeLocal(RECENT_KEY, [snapshot, ...list.filter((item) => topicId(item) !== id)].slice(0, 6));
}

/** Анализы, запущенные из этого браузера: по ним приходит уведомление «отчёт готов». */
export interface TrackedRun {
  requestId: string;
  query: string;
  startedAt: string;
}
