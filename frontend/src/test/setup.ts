/**
 * Vitest environment setup.
 *
 * Note what is deliberately absent: there is no mock service worker and no fake
 * backend. Tests here exercise pure logic and component rendering; where a test
 * needs a transport boundary it stubs `fetch` for that single case. Anything
 * that would amount to a simulated API belongs in the E2E stack, which runs
 * against the real services (FR-06.2).
 */
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
});

// jsdom implements neither of these, and the layout primitives use both.
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string): MediaQueryList =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }) satisfies MediaQueryList,
  });
}

if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class ResizeObserver {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

// Оболочка прокручивает страницу наверх при переходе, а окна — нативный <dialog>: jsdom не умеет ни
// того, ни другого. Заглушки повторяют наблюдаемое поведение: `open` и событие `close`.
window.scrollTo = (): void => undefined;

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement): void {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement): void {
    if (!this.hasAttribute('open')) return;
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
}
