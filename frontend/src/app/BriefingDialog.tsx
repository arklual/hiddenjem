/**
 * Записка и закладки: окно записки и действия «В записку» / «Отслеживать тему» с уведомлениями.
 *
 * Записка копит темы из разных отчётов и скачивается одним файлом — её отправляют тем, кто отчёт
 * не открывал.
 */
import { Link, useNavigate } from 'react-router-dom';
import { useToast } from '@/components/toast/ToastContext';
import { RELIABILITY_LABEL } from '@/features/report/topicModel';
import {
  BRIEFING_KEY,
  WATCH_KEY,
  readLocal,
  toggleTopic,
  topicId,
  useLocal,
  writeLocal,
  type TopicSnapshot,
} from '@/lib/localState';
import { pluralize } from '@/lib/format';
import { useDialog } from '@/ui/DialogProvider';
import { HjIcon } from '@/ui/HjIcon';
import { briefingMarkdown, downloadFile } from './briefing';

const TOPICS = { one: 'тема', few: 'темы', many: 'тем' };

function BriefingList({ onNavigate }: { onNavigate: () => void }): React.ReactElement {
  const [items, setItems] = useLocal<TopicSnapshot[]>(BRIEFING_KEY, []);
  if (items.length === 0) {
    return (
      <div className="empty">
        <h3>Записка пуста</h3>
        <p className="body-muted">
          Добавляйте темы кнопкой «В записку» в отчёте или в карточке темы.
        </p>
      </div>
    );
  }
  return (
    <>
      <p className="body-muted">
        В записку попадут название, стадия, балл, надёжность с оговорками и ключевые источники — её
        можно отправить комитету, не открывая отчёт.
      </p>
      <ul className="brief-list">
        {items.map((item) => (
          <li key={topicId(item)}>
            <span className="od-stack" style={{ '--od-gap': '0px' } as React.CSSProperties}>
              <Link
                to={`/reports/${item.reportId}/trends/${encodeURIComponent(item.trendKey)}`}
                onClick={onNavigate}
                lang={item.title === item.original ? 'en' : undefined}
              >
                {item.title}
              </Link>
              <span className="s">
                {item.query} · место {item.rank} ·{' '}
                {RELIABILITY_LABEL[item.reliability].toLowerCase()}
              </span>
            </span>
            <button
              type="button"
              className="icon-btn"
              aria-label={`Убрать «${item.title}» из записки`}
              onClick={() => setItems(items.filter((other) => topicId(other) !== topicId(item)))}
            >
              <HjIcon name="x" />
            </button>
          </li>
        ))}
      </ul>
    </>
  );
}

export function useBriefing(): {
  openBriefing: () => void;
  toggleBrief: (snapshot: TopicSnapshot) => void;
  toggleWatch: (snapshot: TopicSnapshot) => void;
} {
  const dialog = useDialog();
  const toast = useToast();
  const navigate = useNavigate();

  const openBriefing = (): void => {
    const items = readLocal<TopicSnapshot[]>(BRIEFING_KEY, []);
    dialog.open({
      title: 'Записка для комитета',
      body: <BriefingList onNavigate={dialog.close} />,
      actions:
        items.length > 0
          ? [
              {
                label: 'Очистить',
                onClick: () => {
                  writeLocal(BRIEFING_KEY, []);
                  toast.show('Записка очищена');
                },
              },
              {
                label: 'Скачать записку',
                primary: true,
                autoFocus: true,
                onClick: () => {
                  const current = readLocal<TopicSnapshot[]>(BRIEFING_KEY, []);
                  downloadFile(
                    'hiddenjem-zapiska.md',
                    briefingMarkdown(current),
                    'text/markdown;charset=utf-8',
                  );
                  toast.show('Файл hiddenjem-zapiska.md скачан');
                },
              },
            ]
          : [{ label: 'Понятно', primary: true, autoFocus: true }],
    });
  };

  const toggleBrief = (snapshot: TopicSnapshot): void => {
    const added = toggleTopic(BRIEFING_KEY, snapshot);
    const count = readLocal<TopicSnapshot[]>(BRIEFING_KEY, []).length;
    if (added) {
      toast.show(`Тема добавлена в записку · в ней ${pluralize(count, TOPICS)}`, {
        action: { label: 'Открыть записку', onClick: openBriefing },
      });
    } else {
      toast.show('Тема убрана из записки');
    }
  };

  const toggleWatch = (snapshot: TopicSnapshot): void => {
    const added = toggleTopic(WATCH_KEY, snapshot);
    if (added) {
      toast.show('Тема на радаре: она будет в «Отслеживаемых темах» на первом экране', {
        action: { label: 'На радар', onClick: () => navigate('/radar') },
      });
    } else {
      toast.show('Тема больше не отслеживается');
    }
  };

  return { openBriefing, toggleBrief, toggleWatch };
}
