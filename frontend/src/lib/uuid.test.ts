import { afterEach, describe, expect, it, vi } from 'vitest';

import { randomUuid } from './uuid';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('randomUuid', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('выдаёт UUID, когда браузер даёт randomUUID', () => {
    expect(randomUuid()).toMatch(UUID_V4);
  });

  it('выдаёт UUID и без randomUUID — так интерфейс открыт по HTTP', () => {
    // Ровно то, что на стенде: контекст незащищённый, метода нет. До появления запасного пути
    // здесь падало «crypto.randomUUID is not a function», и анализ не запускался вовсе.
    vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) });

    expect(randomUuid()).toMatch(UUID_V4);
  });

  it('выдаёт UUID и совсем без crypto', () => {
    vi.stubGlobal('crypto', undefined);

    expect(randomUuid()).toMatch(UUID_V4);
  });

  it('не повторяется', () => {
    const seen = new Set(Array.from({ length: 500 }, () => randomUuid()));

    expect(seen.size).toBe(500);
  });
});
