/**
 * Оболочка Hiddenjem: три раздела, поиск «Писали ли мы про это?» и общее меню.
 *
 * Главный путь — запустить анализ и прочитать отчёт — не должен тонуть среди служебных разделов,
 * поэтому в шапке их три: Радар, Новый анализ, Отчёты. Источники и словарь направлений уходят в
 * меню рядом со справкой. На телефоне разделы переезжают в нижнюю панель.
 *
 * Входа нет, одна организация, API открыт: в меню нет ни имени, ни выхода, и служебные пункты видны
 * всем — прятать их было бы не от кого.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useFeature } from '@/lib/features';
import { DialogProvider, useDialog } from '@/ui/DialogProvider';
import { HjIcon, type HjIconName } from '@/ui/HjIcon';
import { Menu } from '@/ui/Menu';
import { CommandPalette } from './CommandPalette';
import { RunWatcher } from './RunWatcher';
import { howtoDialog, shortcutsDialog } from './guides';

const SECTIONS: ReadonlyArray<{
  to: string;
  label: string;
  short: string;
  icon: HjIconName;
  also: RegExp;
}> = [
  { to: '/radar', label: 'Радар', short: 'Радар', icon: 'radar', also: /^\/radar/ },
  { to: '/new', label: 'Новый анализ', short: 'Анализ', icon: 'plus', also: /^\/(new|runs)/ },
  { to: '/reports', label: 'Отчёты', short: 'Отчёты', icon: 'file', also: /^\/reports/ },
];

/** Служебные экраны. Список постоянный: ролей нет, и пункт видит каждый. */
const SERVICE: ReadonlyArray<{ to: string; label: string; icon: HjIconName }> = [
  { to: '/sources', label: 'Источники', icon: 'database' },
  { to: '/admin/lexicon', label: 'Словарь направлений', icon: 'book' },
];

function isTyping(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  return (
    !!element &&
    (element.tagName === 'INPUT' ||
      element.tagName === 'TEXTAREA' ||
      element.tagName === 'SELECT' ||
      element.isContentEditable)
  );
}

/** Путь без вкладки отчёта: переключение вкладок — не переход на другую страницу. */
function pageKey(pathname: string): string {
  return pathname.replace(/\/(excluded|changes)$/, '');
}

function Shell(): React.ReactElement {
  const location = useLocation();
  const dialog = useDialog();
  const main = useRef<HTMLElement>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const previousPage = useRef<string | null>(null);

  // Радар выключается реестром фич — пункт, ведущий на выключенный экран, хуже отсутствующего.
  const radarOn = useFeature('radar');
  const sections = SECTIONS.filter((section) => radarOn || section.to !== '/radar');
  const openPalette = useCallback(() => setPaletteOpen(true), []);

  // Смена страницы: наверх, фокус на заголовок (экранный диктор объявляет, куда пришли), короткое
  // появление. Смена вкладки отчёта страницей не считается — фокус остаётся на вкладке.
  useEffect(() => {
    const key = pageKey(location.pathname);
    const first = previousPage.current === null;
    if (previousPage.current === key) return;
    previousPage.current = key;
    window.scrollTo(0, 0);
    const element = main.current;
    if (!element) return;
    element.classList.remove('enter');
    void element.offsetWidth;
    element.classList.add('enter');
    if (!first) {
      const heading = element.querySelector<HTMLElement>('h1');
      if (heading) {
        heading.tabIndex = -1;
        heading.focus({ preventScroll: true });
      }
    }
  }, [location.pathname]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        openPalette();
        return;
      }
      if (document.querySelector('dialog[open]')) return;
      if (isTyping(event.target) || event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key === '/') {
        event.preventDefault();
        openPalette();
      } else if (event.key === '?') {
        event.preventDefault();
        dialog.open(shortcutsDialog());
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [dialog, openPalette]);

  const isCurrent = (section: (typeof SECTIONS)[number]): boolean =>
    section.also.test(location.pathname);

  return (
    <>
      <a className="skip-link" href="#main">
        Перейти к содержимому
      </a>
      <header className="app-header">
        <div className="app-header__inner">
          <NavLink className="brand" to="/radar" aria-label="Hiddenjem — на радар">
            <span className="brand__mark">
              <HjIcon name="logo" />
            </span>
            <span className="brand__text">Hiddenjem</span>
          </NavLink>
          <nav className="nav" aria-label="Основная навигация">
            {sections.map((section) => (
              <Link
                key={section.to}
                to={section.to}
                aria-current={isCurrent(section) ? 'page' : undefined}
              >
                {section.label}
              </Link>
            ))}
          </nav>
          <div className="header-actions">
            <button
              className="search-trigger"
              type="button"
              aria-label="Поиск по темам во всех отчётах"
              aria-keyshortcuts="Control+K Meta+K /"
              onClick={openPalette}
            >
              <HjIcon name="search" size={18} />
              <span>Писали ли мы про это?</span>
              <kbd>⌘K</kbd>
            </button>
            <Menu
              triggerClassName="menu-trigger"
              // Имя задано явно: на узком экране подпись скрыта и кнопка остаётся одной иконкой.
              triggerLabel="Меню"
              trigger={
                <>
                  <HjIcon name="menu" size={18} />
                  <span>Меню</span>
                </>
              }
              label="Меню"
            >
              {(close) => (
                <>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      close();
                      dialog.open(howtoDialog());
                    }}
                  >
                    <HjIcon name="book" size={18} />
                    Как читать отчёт
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      close();
                      dialog.open(shortcutsDialog());
                    }}
                  >
                    <HjIcon name="keyboard" size={18} />
                    Горячие клавиши
                  </button>
                  {SERVICE.map((item) => (
                    <NavLink key={item.to} to={item.to} role="menuitem" onClick={close}>
                      <HjIcon name={item.icon} size={18} />
                      {item.label}
                    </NavLink>
                  ))}
                </>
              )}
            </Menu>
          </div>
        </div>
      </header>

      <main id="main" className="page" tabIndex={-1} ref={main}>
        <Outlet />
      </main>

      <nav className="bottom-nav" aria-label="Разделы">
        {sections.map((section) => (
          <Link
            key={section.to}
            to={section.to}
            aria-current={isCurrent(section) ? 'page' : undefined}
          >
            <span className="pill">
              <HjIcon name={section.icon} size={22} />
            </span>
            {section.short}
          </Link>
        ))}
        <button type="button" onClick={openPalette}>
          <span className="pill">
            <HjIcon name="search" size={22} />
          </span>
          Поиск
        </button>
      </nav>

      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      <RunWatcher />
    </>
  );
}

export function AppLayout(): React.ReactElement {
  return (
    <DialogProvider>
      <Shell />
    </DialogProvider>
  );
}
