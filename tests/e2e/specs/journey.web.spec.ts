import { readFile } from 'node:fs/promises';
import { expect, test, type Page } from '@playwright/test';
import { CORPUS_DOMAIN } from '../support/api';

/**
 * The analyst's journey through the real UI against the real stack.
 *
 * Selectors are role- and label-based on purpose: such a test fails when the user-visible
 * behaviour breaks, and survives a restyle. A test bound to CSS classes fails on both, which
 * teaches the team to distrust it.
 */
/**
 * Дождаться, пока отчёт наполнится.
 *
 * Адрес `/reports/{id}` появляется в момент приёма запроса, а карточки тем — когда анализ досчитан,
 * то есть до полутора минут (NFR-P2 даёт 90 секунд на новое направление). Обычное ожидание в
 * пятнадцать секунд проверяло бы скорость машины, а не продукт: страница открыта, на ней идут
 * стадии, а тест уже объявляет отказ. Ровно на этом падали шесть браузерных сценариев подряд.
 */
async function waitForReportContent(page: Page) {
  await expect(page.getByRole('article').first()).toBeVisible({ timeout: 150_000 });
}

/*
 * Направление здесь английское (`CORPUS_DOMAIN`): проверяется путь по интерфейсу — стадии, отчёт,
 * карточка темы, — а не понимание русской формулировки. Последнее проверяется отдельными
 * сценариями уровня API.
 */
test.describe('Путь аналитика', () => {
  /**
   * Открыть экран нового анализа.
   *
   * Входа в продукте нет: интерфейс открывается сразу, без учётной записи и без вводного тура,
   * который раньше встречал каждого нового пользователя.
   */
  async function openNewAnalysis(page: Page): Promise<void> {
    await page.goto('/new');
    await expect(page.getByLabel('Технологическое направление')).toBeVisible();
  }

  test('интерфейс открывается сразу на радаре, без входа', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveURL(/\/radar/);
    await expect(page.getByRole('heading', { name: 'Радар' })).toBeVisible();
  });

  test('экран нового анализа доступен без входа', async ({ page }) => {
    await openNewAnalysis(page);
  });

  test('анализ направления показывает стадии и приводит к ТОП-15', async ({ page }) => {
    await openNewAnalysis(page);

    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();

    // Progress must be staged, not a bare spinner: the user waits up to 90 seconds and needs to
    // see that the system is working through the pipeline (NFR-U2).
    await expect(page.getByRole('status').or(page.getByRole('progressbar'))).toBeVisible({ timeout: 20_000 });

    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);
    const trendCards = page.getByRole('article');
    await expect(trendCards.first()).toBeVisible({ timeout: 30_000 });
    expect(await trendCards.count()).toBeGreaterThan(0);
  });

  test('карточка тренда раскрывает мотивацию, кейс, источники и методологию', async ({ page }) => {
    await openNewAnalysis(page);
    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);

    await page.getByRole('article').first().getByRole('link').first().click();
    await expect(page).toHaveURL(/\/trends\//);

    // `.first()`: слово встречается и подписью блока, и в оговорке о дословных цитатах.
    await expect(page.getByText(/Проблема/).first()).toBeVisible();
    await expect(page.getByText(/Преимущество/)).toBeVisible();

    // Every claim must be traceable to a primary source (BR-A6): at least one outbound link.
    const sourceLink = page.getByRole('link', { name: /arxiv|doi|patent|github|http/i }).first();
    await expect(sourceLink).toBeVisible();
    expect(await sourceLink.getAttribute('href')).toMatch(/^https?:\/\//);

    // The methodology breakdown is the product's credibility claim; it must be reachable.
    await expect(page.getByText(/Новизна/)).toBeVisible();
    await expect(page.getByText(/Диффузия/)).toBeVisible();
    await expect(page.getByText(/em-\d+\.\d+\.\d+/)).toBeVisible();
  });

  test('карточка отвечает по-русски и подаёт цитату как цитату', async ({ page }) => {
    // Мотивация приводится дословной цитатой из источника, а источники в корпусе почти все
    // английские. Это решение, а не недоделка: перевод перестал бы быть цитатой, а пересказ нельзя
    // проверить по первоисточнику (ADR-0010). Но аналитик, не читающий по-английски, должен
    // получить ответ всё равно — по числам, которые движок уже посчитал.
    await openNewAnalysis(page);
    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);

    await page.getByRole('article').first().getByRole('link').first().click();
    await expect(page).toHaveURL(/\/trends\//);

    // Наблюдения — по-русски и до цитаты: ответ нужен в начале блока, а не после абзаца на чужом
    // языке.
    await expect(page.getByLabel('Что наблюдается по теме')).toBeVisible();

    // Цитата размечена цитатой. Абзацем она читается как текст платформы на ломаном языке.
    const quote = page.locator('blockquote').first();
    await expect(quote).toBeVisible();

    // И объяснено, почему она не переведена: читателю ответ нужен в ту же секунду, а не в
    // методологии через две страницы.
    await expect(page.getByText(/дословными цитатами из источников/)).toBeVisible();
  });

  test('радар показывает состояние отслеживаемых направлений', async ({ page }) => {
    await page.goto('/radar');

    // Экран, с которого аналитик начинает день. Даже пустой он обязан объяснить, что делать
    // дальше, а не показывать пустоту.
    await expect(page.getByRole('heading', { name: 'Радар' })).toBeVisible();
    // Картина отвечает, какое направление открыть первым; строки ниже — что внутри каждого.
    // Пустой радар её не рисует: точка в начале координат утверждала бы «ничего не происходит и
    // верить нечему» про направление, на которое ещё никто не смотрел.
    // `.first()`: на пустом радаре обе формулировки присутствуют одновременно — сводка «отслеживается
    // направлений: 0» и приглашение «пока нечего отслеживать». Проверяется, что ответ дан, а не
    // какой именно из двух, поэтому строгий режим локатора здесь мешает, а не помогает.
    await expect(
      page.getByText(/Отслеживается направлений|Пока нечего отслеживать/).first(),
    ).toBeVisible({ timeout: 30_000 });
  });

  test('открытое направление перестаёт числиться непросмотренным', async ({ page }) => {
    // Единственный шаг, где отметка визита проверяется целиком: POST /seen, строка в базе, новое
    // чтение радара. Юниты видят только половины — клиентское сравнение двух моментов и запрос к
    // репозиторию, — а между ними лежит ровно то место, где эта фича уже один раз оказалась мёртвой.
    await page.goto('/radar');
    await expect(page.getByRole('heading', { name: 'Радар' })).toBeVisible();

    const open = page.getByRole('button', { name: /Открыть отчёт/ }).first();
    if ((await open.count()) === 0) {
      test.skip(true, 'нечего открывать: ни одно направление ещё не анализировалось');
    }
    const direction = page.locator('[id^="direction-"]').first();
    const savedDomainId = ((await direction.getAttribute('id')) ?? '').replace('direction-', '');

    await open.click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 30_000 });
    await waitForReportContent(page);

    await page.goto('/radar');
    await expect(page.getByRole('heading', { name: 'Радар' })).toBeVisible();
    // Пометка снимается только с открытого направления, а не со всего экрана: обратное означало бы,
    // что визит на одно направление гасит новости по остальным.
    await expect(
      page.locator(`#direction-${savedDomainId}`).getByText('новое с прошлого визита'),
    ).toHaveCount(0, { timeout: 30_000 });
  });

  test('записку можно скачать, и в ней есть оговорки', async ({ page }) => {
    // Единственный шаг, где документ проходит весь путь: отрисовка на сервере, content-type,
    // Content-Disposition, шлюз, скачивание в браузере. Юниты видят только строку,
    // а всё остальное — это ровно те места, где выгрузка уже однажды могла не доехать.
    await openNewAnalysis(page);
    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Записка' }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/\.md$/);

    const path = await file.path();
    const text = await readFile(path, 'utf8');
    // Оговорки обязаны быть выше тем: сноска в конце отбрасывается при копировании первой половины
    // документа, и до комитета доезжает только та, которую нельзя не увидеть.
    expect(text).toContain('## Оговорки');
    expect(text.indexOf('## Оговорки')).toBeLessThan(text.indexOf('## Темы'));
    expect(text).toContain('Недоступные источники:');
  });

  test('поиск по темам находит то, что уже посчитано, и ведёт в отчёт', async ({ page }) => {
    // Единственный шаг, где вся цепочка проходится целиком: запрос → JPQL по трём таблицам с
    // границей организации → группировка в домене → экран. Юниты видят только концы этой цепочки,
    // а средний слой не знает ни про контроллер, ни про то, доедет ли ответ до карточки.
    await page.goto('/topics');
    await expect(page.getByRole('heading', { name: 'Поиск по темам' })).toBeVisible();

    await page.getByLabel('Фрагмент названия темы').fill('learning');
    await page.getByRole('button', { name: 'Найти' }).click();

    // Ответ обязан быть одним из двух названных: найденное или честная пустота. Крутящийся
    // индикатор и пустой экран без объяснения — это третье состояние, которого быть не должно.
    await expect(page.getByText(/Найдено тем|Ничего не нашлось/)).toBeVisible({ timeout: 30_000 });

    const topic = page.getByRole('heading', { level: 2 }).first();
    if ((await topic.count()) === 0) {
      test.skip(true, 'корпус пуст: искать ещё нечего');
    }
    // Вхождение обязано открываться: «мы про это писали» без проверяемой ссылки — утверждение,
    // которое не на чем опровергнуть.
    await page.locator('a[href^="/reports/"]').first().click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 30_000 });
    await waitForReportContent(page);
  });

  test('отчёт открывается с портретом направления и его оговорками', async ({ page }) => {
    await openNewAnalysis(page);
    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);

    // Сводка — первое, что читают, и она обязана нести оговорку о тонкой доказательной базе:
    // убедительное умолчание здесь опаснее отсутствия сводки.
    await expect(page.getByText('Портрет направления')).toBeVisible();
    await expect(page.getByText(/тонкой доказательной базе/)).toBeVisible();
    // Слово встречается дважды: в строке сводки и подписью в списке. Проверяется наличие оговорки.
    await expect(page.getByText(/Недоступны/).first()).toBeVisible();
  });

  test('первая версия отчёта честно сообщает, что сравнивать не с чем', async ({ page }) => {
    await openNewAnalysis(page);
    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);

    // "Nothing to compare with" and "nothing changed" demand different actions from the analyst, so
    // the panel must never answer one with the other.
    await expect(page.getByText('Что изменилось')).toBeVisible();
    await expect(page.getByText(/первая версия отчёта|Появились|не изменились/)).toBeVisible({
      timeout: 30_000,
    });
  });

  test('отчёт объясняет, почему ожидаемой темы в нём нет', async ({ page }) => {
    await openNewAnalysis(page);
    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);

    // The engine replays the whole analysis to answer, so this is the slowest read in the product —
    // and the one that decides whether an analyst trusts the ranking at all.
    await page.getByLabel('Технология').fill('software bill of materials');
    await page.getByRole('button', { name: /проследить/i }).click();

    // Either a trace with the stage that removed it, or an explicit "never entered the pipeline".
    const verdict = page.getByText(/отброшен|в отчёте|не встретился в корпусе/).first();
    await expect(verdict).toBeVisible({ timeout: 120_000 });
  });

  test('история запросов содержит выполненный анализ', async ({ page }) => {
    await openNewAnalysis(page);
    await page.getByLabel('Технологическое направление').fill(CORPUS_DOMAIN);
    await page.getByRole('button', { name: /найти|запустить|анализ/i }).click();
    await expect(page).toHaveURL(/\/reports\//, { timeout: 150_000 });
    await waitForReportContent(page);

    // Организация одна и входа нет, поэтому история общая: деления на «мои» и «чужие» запуски нет.
    // `/history` — старый адрес, интерфейс уводит с него на список отчётов.
    await page.goto('/reports');

    await expect(page.getByText(new RegExp(CORPUS_DOMAIN.slice(0, 20), 'i')).first()).toBeVisible();
  });

  test('основной сценарий проходится только с клавиатуры', async ({ page }) => {
    // WCAG 2.1.1 — an analyst who cannot use a mouse must still be able to run an analysis.
    await openNewAnalysis(page);
    const field = page.getByLabel('Технологическое направление');

    // До поля добираемся табуляцией, а не щелчком: проверяется ровно то, что поле достижимо с
    // клавиатуры. Предел — чтобы потерянный фокус падал понятной ошибкой, а не зависанием.
    let reached = false;
    for (let i = 0; i < 40 && !reached; i++) {
      await page.keyboard.press('Tab');
      reached = await field.evaluate((element) => element === document.activeElement);
    }
    expect(reached, 'поле направления недостижимо табуляцией').toBe(true);

    await page.keyboard.type(CORPUS_DOMAIN);
    // Escape закрывает список подсказок, чтобы Enter отправил форму, а не выбрал подсказку.
    await page.keyboard.press('Escape');
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/(runs|reports)\//, { timeout: 150_000 });
  });

  test('интерфейс не ломается при отсутствии данных', async ({ page }) => {
    await page.goto('/history');

    // Либо строки, либо явная пустота — но никогда не пустой экран без объяснения.
    //
    // Ожиданием, а не мгновенной проверкой: `isVisible()` спрашивает про текущий кадр и не ждёт.
    // Сразу после перехода история ещё грузится, на экране скелетон — и проверка объявляла отказ
    // ровно там, где интерфейс вёл себя правильно. Ошибка была в вопросе, а не в ответе.
    //
    // `.first()` у пустоты: она состоит из заголовка и пояснения, и оба попадают под условие.
    await expect(
      page.getByRole('table').or(page.getByText(/пока нет|ничего не найдено|пусто/i).first()),
    ).toBeVisible();
  });
});
