import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Слияние классов Tailwind.
 *
 * `clsx` собирает условные классы, `twMerge` разрешает конфликты в пользу последнего: без него
 * `cn('p-2', props.className)` с `p-4` снаружи давал бы оба класса сразу, и какой победит, решал бы
 * порядок правил в собранном CSS — то есть случайность. Это и есть способ, которым компонент
 * остаётся настраиваемым снаружи.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
