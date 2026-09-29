/**
 * Адрес экрана поиска с подставленными направлением и параметрами.
 *
 * Существует ради одного: чтобы совет со страницы отказа можно было выполнить нажатием, а не
 * повторным набором запроса. Тот же приём уже используется подсказкой «возможно, вы имели в виду»
 * на отчёте с нераспознанным направлением.
 *
 * В адрес попадают только те параметры, что заданы: пустое значение в строке запроса экран
 * прочитал бы как «ноль», а «не задано» и «ноль» — разные вещи.
 */
import type { AnalysisParameters } from '@/api/types';

export function retryPath(query: string, parameters: AnalysisParameters | undefined): string {
  const search = new URLSearchParams({ query });
  const source = parameters ?? {};
  if (source.yearsWindow !== undefined) {
    search.set('yearsWindow', String(source.yearsWindow));
  }
  if (source.topN !== undefined) {
    search.set('topN', String(source.topN));
  }
  if (source.minConfidence !== undefined) {
    search.set('minConfidence', String(source.minConfidence));
  }
  // Повтор должен быть тем же прогоном, а не похожим: параметры, потерянные по дороге, молча
  // подменяются умолчаниями формы, и аналитик сравнивает два разных запроса, думая, что один.
  if (source.includeMature !== undefined) {
    search.set('includeMature', source.includeMature ? '1' : '0');
  }
  // Режим — часть вопроса, а не его оформление: повтор без него пересчитал бы качественный запрос
  // быстрым, и аналитик сравнивал бы два разных ответа, считая, что повторил один.
  if (source.mode !== undefined) {
    search.set('mode', source.mode);
  }
  // Пустой список означает «все классы» — это умолчание, и писать его в адрес незачем.
  for (const sourceClass of source.sourceClasses ?? []) {
    search.append('sourceClasses', sourceClass);
  }
  return `/new?${search.toString()}`;
}
