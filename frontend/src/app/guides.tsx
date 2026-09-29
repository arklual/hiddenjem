/**
 * Справка, которая открывается окном: «Как читать отчёт» и горячие клавиши.
 *
 * Вместо модального тура из четырёх шагов при первом входе — одна строка на радаре и это окно по
 * запросу. Объяснение живёт рядом с тем, что объясняет, а не отдельно от него.
 */
import { RelTag, StageTag } from '@/features/report/TopicBits';
import { writeLocal, INTRO_KEY } from '@/lib/localState';
import type { DialogConfig } from '@/ui/DialogProvider';

export function howtoDialog(): DialogConfig {
  return {
    title: 'Как читать отчёт',
    body: (
      <>
        <ol className="guide">
          <li>
            <div>
              <strong>Hiddenjem ищет то, что только набирает силу</strong>
              <span>
                Мейнстрим отсеивается намеренно, поэтому в отчёте бывают незнакомые названия, а
                знакомых может не быть. Их ищите во вкладке «Что не попало».
              </span>
            </div>
          </li>
          <li>
            <div>
              <strong>В поле пишут направление, а не тему</strong>
              <span>
                «Финтех», а не «токенизированные депозиты». Узкие темы система должна найти сама.
              </span>
            </div>
          </li>
          <li>
            <div>
              <strong>Анализ идёт минуты — ждать на странице не нужно</strong>
              <span>
                Быстрый режим — до 20 минут, качественный — 30–40. Готовый отчёт появится в
                «Отчётах» и на радаре, а мы покажем уведомление.
              </span>
            </div>
          </li>
          <li>
            <div>
              <strong>Одна метка надёжности собирает все оговорки</strong>
              <span>
                Причины — в превью темы и в разделе «Насколько верить» её карточки. Метка их не
                заменяет, а сводит в одно слово.
              </span>
            </div>
          </li>
        </ol>
        <div className="legend-grid">
          <div>
            <StageTag stage="EMBRYONIC" />
            <span>Публикаций ещё мало, но тренд положительный</span>
          </div>
          <div>
            <StageTag stage="EMERGING" />
            <span>Устойчивый рост при умеренном объёме</span>
          </div>
          <div>
            <StageTag stage="ACCELERATING" />
            <span>Быстрый рост со всплеском либо заметным объёмом</span>
          </div>
          <div>
            <StageTag stage="MATURING" />
            <span>Рост замедлился — такие темы по умолчанию не попадают в отчёт</span>
          </div>
          <div>
            <RelTag level="ok" />
            <span>Тема своего направления, данных достаточно, место не зависит от весов</span>
          </div>
          <div>
            <RelTag level="check" />
            <span>Есть оговорка: место плавает, тема может быть из соседнего направления</span>
          </div>
          <div>
            <RelTag level="low" />
            <span>Мало документов или низкая уверенность — вывод предварительный</span>
          </div>
        </div>
      </>
    ),
    actions: [
      {
        label: 'Понятно',
        primary: true,
        autoFocus: true,
        onClick: () => writeLocal(INTRO_KEY, true),
      },
    ],
  };
}

const SHORTCUTS: ReadonlyArray<[string, string]> = [
  ['⌘K, Ctrl+K или /', 'Поиск по темам во всех отчётах'],
  ['↑ ↓ или J K', 'Выбрать тему в отчёте'],
  ['Enter', 'Открыть выбранную тему'],
  ['[ и ]', 'Предыдущая и следующая тема в карточке'],
  ['Esc', 'Закрыть окно или меню'],
  ['?', 'Эта справка'],
];

export function shortcutsDialog(): DialogConfig {
  return {
    title: 'Горячие клавиши',
    body: (
      <table className="keys">
        <caption className="sr-only">Горячие клавиши</caption>
        <tbody>
          {SHORTCUTS.map(([keys, meaning]) => (
            <tr key={keys}>
              <td>
                <kbd>{keys}</kbd>
              </td>
              <td>{meaning}</td>
            </tr>
          ))}
        </tbody>
      </table>
    ),
    actions: [{ label: 'Закрыть', primary: true, autoFocus: true }],
  };
}
