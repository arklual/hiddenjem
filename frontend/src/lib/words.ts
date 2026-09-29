/**
 * Слова и короткие форматы, которые повторяются на экранах Hiddenjem.
 */
import { pluralize } from './format';

export const DOCUMENTS = { one: 'документ', few: 'документа', many: 'документов' };
export const TOPICS = { one: 'тема', few: 'темы', many: 'тем' };
export const SOURCES = { one: 'источник', few: 'источника', many: 'источников' };
export const DIRECTIONS = { one: 'направление', few: 'направления', many: 'направлений' };
export const RUNS = { one: 'запуск', few: 'запуска', many: 'запусков' };
export const MINUTES = { one: 'минута', few: 'минуты', many: 'минут' };

export const documents = (count: number): string => pluralize(count, DOCUMENTS);
export const topics = (count: number): string => pluralize(count, TOPICS);

/** «27 сентября» — без года, если он текущий. */
export function shortDate(input: string | null | undefined, now = new Date()): string {
  if (!input) return '—';
  const date = new Date(input);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleDateString('ru-RU', {
    day: 'numeric',
    month: 'long',
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
  });
}

/** «сегодня в 14:13», «вчера в 21:02», «25 сентября в 09:46». */
export function whenDone(input: string | null | undefined, now = new Date()): string {
  if (!input) return '—';
  const date = new Date(input);
  if (Number.isNaN(date.getTime())) return '—';
  const time = date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  const day = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const that = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const diff = Math.round((day - that) / 86_400_000);
  if (diff === 0) return `сегодня в ${time}`;
  if (diff === 1) return `вчера в ${time}`;
  return `${shortDate(input, now)} в ${time}`;
}

/** Длительность запуска в минутах, от отправки до завершения. */
export function runMinutes(
  submittedAt: string,
  finishedAt: string | null | undefined,
): number | null {
  if (!finishedAt) return null;
  const ms = new Date(finishedAt).getTime() - new Date(submittedAt).getTime();
  return Number.isFinite(ms) && ms > 0 ? Math.max(1, Math.round(ms / 60_000)) : null;
}

export function modeLabel(mode: string | undefined): string {
  return mode === 'quality' ? 'Качественный' : 'Быстрый';
}
