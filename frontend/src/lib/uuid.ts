/**
 * Идентификатор запроса, который не зависит от того, как открыт интерфейс.
 *
 * `crypto.randomUUID` браузер даёт только в защищённом контексте: по HTTPS или с localhost. Стенд
 * открыт по обычному HTTP на адрес, и там метода просто нет — вызов падал с «crypto.randomUUID is
 * not a function», а пользователь видел «Произошла непредвиденная ошибка» и не мог запустить ни
 * одного анализа. Разработчик этого не встречал никогда: у него localhost, то есть контекст
 * защищённый.
 *
 * `crypto.getRandomValues` таким ограничением не связан и есть везде, где есть `crypto`, поэтому
 * основной путь — собрать UUID из него. Совсем без `crypto` остаются разве что очень старые
 * окружения; для них есть последний путь на `Math.random`. Он криптографически не стоек, и это
 * здесь допустимо: значение служит ключом идемпотентности — склеить двойное нажатие, — а не
 * секретом. Ключ живёт до ответа сервера и ничего не защищает.
 */
export function randomUuid(): string {
  const webCrypto = globalThis.crypto;

  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }

  const bytes = new Uint8Array(16);
  if (typeof webCrypto?.getRandomValues === 'function') {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }

  // Версия 4 и вариант RFC 4122 — те же биты, что расставил бы сам браузер.
  // `?? 0` не про длину массива — она заведомо 16, — а про `noUncheckedIndexedAccess`: с ним
  // индексное обращение типизировано как `number | undefined`, и без этого проверка типов падает.
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;

  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0'));
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10, 16).join(''),
  ].join('-');
}
