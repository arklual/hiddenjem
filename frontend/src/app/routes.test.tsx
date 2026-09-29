import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '@/components/toast/ToastProvider';
import { AppRoutes } from './routes';

/**
 * Входа нет, одна организация, API открыт: таблица маршрутов одна и открыта целиком.
 *
 * Проверка идёт по адресам, а не по виду шапки. Спрятать ссылку легко и этого мало: адрес, набранный
 * руками или оставшийся в закладке, открыл бы ту же форму входа или тот же профиль. Здесь
 * утверждается более сильное: таких экранов в сборке нет вообще.
 *
 * Оба списка перечислены поимённо, а не выведены из таблицы маршрутов: правило, читающее ту же
 * таблицу, которую проверяет, согласится с любым её содержимым.
 */
const GONE = [
  '/login',
  '/register',
  '/profile',
  '/admin/users',
  '/admin/audit',
  // Страница методологии выведена из продукта вместе с движком методики: старая закладка должна
  // вести в «не найдено», а не в пустой экран.
  '/methodology',
];

/** Три раздела Hiddenjem и два служебных экрана — все открыты без входа. */
const OPEN = ['/radar', '/new', '/reports', '/sources', '/admin/lexicon'];

function renderAt(path: string): void {
  // Транспорт оборван намеренно и только здесь: предмет проверки — какой экран открывается по
  // адресу, и ответ сервера на него не влияет. Живой запрос из jsdom добавил бы к проверке
  // маршрутов зависимость от поднятого стенда.
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.reject(new Error('в этой проверке сети нет'))),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <ToastProvider>
          <AppRoutes />
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('таблица маршрутов', () => {
  it.each(GONE)('не открывает %s', async (path) => {
    renderAt(path);

    expect(await screen.findByText('Такой страницы нет')).toBeInTheDocument();
  });

  it.each(OPEN)('открывает %s', async (path) => {
    // Канарейка: без неё «не открывает» означало бы и «убраны только лишние экраны», и «не
    // открывает вообще ничего», а по зелёному тесту эти два состояния неразличимы.
    renderAt(path);

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.queryByText('Такой страницы нет')).not.toBeInTheDocument();
  });

  it('ведёт прежний адрес запроса на новый анализ', async () => {
    // Ссылки «повторить» из старых выгрузок и закладки на /research не должны превращаться в 404.
    renderAt('/research?query=финтех');

    expect(await screen.findByRole('heading', { name: 'Что ищем?' })).toBeInTheDocument();
    expect(screen.getByLabelText('Технологическое направление')).toHaveValue('финтех');
  });

  it('оставляет выбор режима анализа', async () => {
    // Режим — часть вопроса, а не обвязка: без выбора качественного режима продукт показывал бы
    // только двадцатиминутный ответ и называл бы его ответом целиком.
    renderAt('/new');

    expect(await screen.findByRole('group', { name: 'Режим анализа' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Качественный/ })).toBeInTheDocument();
  });
});
