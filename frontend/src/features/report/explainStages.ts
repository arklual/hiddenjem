/**
 * Слова для трассировки термина по конвейеру движка — «где и почему отброшена моя тема».
 *
 * Перечень стадий сверяется с движком тестом `everyPipelineStageHasWords`: стадия без подписи
 * показалась бы сырым ключом, подпись без стадии — обещанием того, чего движок не делает.
 */
/** Human names for the pipeline stages the engine reports. */
const STAGE_TITLES: Record<string, string> = {
  extracted: 'Извлечение терминов',
  min_df: 'Порог по числу документов',
  term_filter: 'Правило имени технологии',
  unigram_termhood: 'Отбор однословных терминов',
  merged: 'Объединение синонимов',
  clustered: 'Кластеризация',
  relevance: 'Отнесение к направлению',
  credibility: 'Достоверность источников',
  evidence: 'Доказательная база',
  mainstream: 'Отсев мейнстримных тем',
  confidence: 'Порог уверенности',
  zero_score: 'Обнуляющий индикатор',
  suppressed: 'Скрыто вашей пометкой «не технология»',
  near_duplicate: 'Повтор темы под другим названием',
  rerank: 'Переранжирование верхушки',
  jury: 'Экспертная проверка',
  top_n: 'Отбор в ТОП',
  ranked: 'В отчёте',
};

/**
 * A stage the engine named but this build has no wording for — shown as its raw key rather than
 * hidden. A silently dropped stage would turn a complete answer into a misleading one.
 */
export function stageTitle(stage: string): string {
  return STAGE_TITLES[stage] ?? stage;
}

function formatDetail(value: unknown): string {
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : value.toFixed(4);
  }
  return String(value);
}

/**
 * Правило, по которому решался вопрос об отнесении темы к направлению.
 *
 * Их два, и они измеряют разное. Не назвать применённое — значит показать аналитику число без
 * единицы измерения: «0.03 при пороге 0.29» одинаково выглядит и для доли документов, и для
 * лексической близости, а выводы из них следуют разные.
 */
const RULE_TITLES: Record<string, string> = {
  'subject-share': 'по кодам рубрик источника',
  'embedding-cut': 'по близости формулировок (запасной путь)',
};

/**
 * Русские подписи к числам, решившим судьбу термина.
 *
 * Ключи приходят машинными — так и надо для JSON, — но аналитику в русском интерфейсе `measure` и
 * `cut` не говорят ничего. Подпись зависит от правила: в одном режиме мера это доля документов
 * темы, отнесённых к направлению, в другом — близость к формулировке запроса.
 *
 * Неизвестный ключ показывается сырым, а не прячется: движок вправе завести новое число, и
 * умолчать о нём значило бы превратить полный ответ в обманчивый — та же причина, по которой так
 * же поступает `stageTitle`.
 */
export function detailTitle(key: string, rule: unknown): string {
  const share = rule === 'subject-share';
  switch (key) {
    case 'measure':
      return share ? 'Доля документов темы в направлении' : 'Близость к формулировке';
    case 'cut':
      return share ? 'Порог доли' : 'Порог близости';
    case 'rule':
      return 'Правило';
    default:
      return key;
  }
}

export function detailValue(key: string, value: unknown, rule: unknown): string {
  if (key === 'rule') {
    return RULE_TITLES[String(value)] ?? String(value);
  }
  // Доля — это проценты. Печатать её как «0.0312» значит заставлять читателя переводить в уме
  // ровно там, где он сравнивает два числа между собой.
  if (
    rule === 'subject-share' &&
    typeof value === 'number' &&
    (key === 'measure' || key === 'cut')
  ) {
    return `${(value * 100).toFixed(1)} %`;
  }
  return formatDetail(value);
}
