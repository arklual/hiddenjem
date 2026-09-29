/**
 * Server-Sent Events over `fetch` (ADR-0012).
 *
 * Why not `EventSource`: the contract declares `Last-Event-ID` as an explicit
 * *header* parameter, which only a manual reader can supply on reconnect, and a
 * `fetch` stream is aborted by the same `AbortSignal` as every other request. So
 * the stream is consumed with `fetch` + a `ReadableStream` reader and framed by
 * `SseParser` below.
 *
 * `SseParser` is deliberately free of I/O so the wire-format handling — multi-line
 * `data:`, comments, CRLF, cross-chunk splits — is unit-testable on its own.
 */

export interface SseEvent {
  /** `event:` field. Per the contract: `progress` | `completed` | `failed` | `heartbeat`. */
  event: string;
  /** Concatenated `data:` lines. */
  data: string;
  /** `id:` field, echoed back as `Last-Event-ID` when reconnecting. */
  id: string | null;
  /** `retry:` field in milliseconds, if the server sent one. */
  retry: number | null;
}

/** Incremental parser for the `text/event-stream` wire format. */
export class SseParser {
  /** Carries an unterminated trailing line between chunks. */
  private pending = '';
  private dataLines: string[] = [];
  private eventType = '';
  private lastId: string | null = null;
  private retry: number | null = null;

  /** Feeds a decoded chunk and returns every event completed by it. */
  push(chunk: string): SseEvent[] {
    const events: SseEvent[] = [];
    // Normalise CR and CRLF to LF; the spec treats all three as line ends.
    this.pending += chunk.replace(/\r\n?/g, '\n');

    let newlineIndex = this.pending.indexOf('\n');
    while (newlineIndex !== -1) {
      const line = this.pending.slice(0, newlineIndex);
      this.pending = this.pending.slice(newlineIndex + 1);
      const event = this.handleLine(line);
      if (event) events.push(event);
      newlineIndex = this.pending.indexOf('\n');
    }
    return events;
  }

  private handleLine(line: string): SseEvent | null {
    // Blank line dispatches the buffered event.
    if (line === '') return this.dispatch();
    // A leading colon marks a comment — commonly used as a keep-alive ping.
    if (line.startsWith(':')) return null;

    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    // A single leading space after the colon is part of the framing, not the data.
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'event':
        this.eventType = value;
        break;
      case 'data':
        this.dataLines.push(value);
        break;
      case 'id':
        // NUL is not a legal id per spec; ignore such a field.
        if (!value.includes('\0')) this.lastId = value;
        break;
      case 'retry': {
        const ms = Number(value);
        if (Number.isInteger(ms) && ms >= 0) this.retry = ms;
        break;
      }
      default:
        break; // Unknown fields are ignored by the spec.
    }
    return null;
  }

  private dispatch(): SseEvent | null {
    if (this.dataLines.length === 0) {
      // Кадр без данных, но с типом — это keep-alive: он ничего не сообщает о состоянии, но
      // доказывает, что поток жив. Проглотить его значит дать таймеру тишины сработать на живом
      // соединении и уйти в опрос без причины. Безымянный пустой кадр по-прежнему пропускаем.
      const eventType = this.eventType;
      this.eventType = '';
      if (eventType === '') return null;
      return { event: eventType, data: '', id: this.lastId, retry: this.retry };
    }
    const event: SseEvent = {
      event: this.eventType || 'message',
      data: this.dataLines.join('\n'),
      id: this.lastId,
      retry: this.retry,
    };
    this.dataLines = [];
    this.eventType = '';
    return event;
  }

  /** Last `id:` seen, for the `Last-Event-ID` header on reconnect. */
  getLastEventId(): string | null {
    return this.lastId;
  }
}

export interface StreamSseOptions {
  url: string;
  lastEventId?: string | null;
  signal: AbortSignal;
  /** Called once the response headers confirm an event stream. */
  onOpen?: () => void;
  onEvent: (event: SseEvent) => void;
}

export class SseHttpError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`Поток событий недоступен (HTTP ${status})`);
    this.name = 'SseHttpError';
    this.status = status;
  }
}

/**
 * Opens the stream and pumps events until the server closes it or `signal`
 * aborts. Resolves on a clean end-of-stream; rejects on transport or HTTP error
 * so the caller can fall back to polling.
 */
export async function streamSse(options: StreamSseOptions): Promise<void> {
  const headers = new Headers({ Accept: 'text/event-stream' });
  if (options.lastEventId) headers.set('Last-Event-ID', options.lastEventId);

  const response = await fetch(options.url, {
    method: 'GET',
    headers,
    signal: options.signal,
    // Long-lived stream: never let a cache sit in the middle of it.
    cache: 'no-store',
  });

  if (!response.ok) throw new SseHttpError(response.status);
  if (!response.body) throw new SseHttpError(response.status);

  options.onOpen?.();

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      // `stream: true` keeps multi-byte UTF-8 sequences intact across chunks —
      // Cyrillic progress messages are two bytes each and do get split.
      for (const event of parser.push(decoder.decode(value, { stream: true }))) {
        options.onEvent(event);
      }
    }
  } finally {
    reader.cancel().catch(() => {
      /* the stream is already going away; nothing useful to do */
    });
  }
}
