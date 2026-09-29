import { describe, expect, it } from 'vitest';
import { safeHref } from './links';

/**
 * Адреса источников приходят из внешних лент, а по ссылке кликает аналитик.
 *
 * `href="javascript:…"` React не блокирует — он предупреждает в разработке и всё равно отрисовывает
 * ссылку. Путь ведёт от чужой RSS-ленты до кода, исполняемого в сессии аналитика.
 */
describe('safeHref', () => {
  it('passes ordinary web links through unchanged', () => {
    expect(safeHref('https://arxiv.org/abs/2403.00001')).toBe('https://arxiv.org/abs/2403.00001');
    expect(safeHref('http://example.org/a(b)c')).toBe('http://example.org/a(b)c');
  });

  it('refuses a script url', () => {
    expect(safeHref('javascript:alert(1)')).toBeUndefined();
  });

  it('refuses schemes disguised by case and whitespace', () => {
    // Сравнение префиксов здесь и ломается: браузер отбрасывает ведущие пробелы и не различает
    // регистр схемы, а startsWith различает.
    expect(safeHref('JaVaScRiPt:alert(1)')).toBeUndefined();
    expect(safeHref('  javascript:alert(1)')).toBeUndefined();
    expect(safeHref('\tjavascript:alert(1)')).toBeUndefined();
  });

  it('refuses data and file urls', () => {
    // data:text/html открывает страницу злоумышленника; file: уводит в файловую систему аналитика.
    expect(safeHref('data:text/html,<script>alert(1)</script>')).toBeUndefined();
    expect(safeHref('file:///etc/passwd')).toBeUndefined();
  });

  it('refuses a relative path', () => {
    // Трактовать чужую строку как путь внутри приложения — значит выдать её за свою страницу.
    expect(safeHref('/admin')).toBeUndefined();
  });

  it('treats absence as absence, not as an error', () => {
    expect(safeHref(undefined)).toBeUndefined();
    expect(safeHref('')).toBeUndefined();
  });
});
