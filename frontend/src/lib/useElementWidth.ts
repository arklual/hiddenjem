import { useEffect, useRef, useState, type RefObject } from 'react';

/**
 * Tracks an element's content width so SVG charts can be laid out in real pixels.
 *
 * Scaling a fixed `viewBox` with `width: 100%` would be simpler, but it scales
 * the type and stroke weights along with the geometry — axis labels end up at a
 * different size in every column of the page. Measuring instead keeps text at
 * exactly the size the design system specifies at any container width.
 */
export function useElementWidth<T extends HTMLElement>(fallback = 640): [RefObject<T>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;

    const measure = (): void => {
      const next = element.clientWidth;
      if (next > 0) setWidth(next);
    };
    measure();

    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }

    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return [ref, width];
}
