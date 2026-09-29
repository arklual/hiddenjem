import { describe, expect, it } from 'vitest';
import { SseParser } from './sse';

describe('SseParser', () => {
  it('parses a complete event', () => {
    const parser = new SseParser();
    const events = parser.push('event: progress\ndata: {"percent":40}\n\n');

    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ event: 'progress', data: '{"percent":40}' });
  });

  it('reassembles an event split across chunks', () => {
    // The real failure mode this guards: a `ReadableStream` hands over arbitrary
    // byte boundaries, so a frame routinely arrives in pieces.
    const parser = new SseParser();

    expect(parser.push('event: prog')).toHaveLength(0);
    expect(parser.push('ress\ndata: {"per')).toHaveLength(0);
    const events = parser.push('cent":40}\n\n');

    expect(events).toHaveLength(1);
    expect(events[0]?.event).toBe('progress');
    expect(events[0]?.data).toBe('{"percent":40}');
  });

  it('joins multi-line data with newlines, per the spec', () => {
    const parser = new SseParser();
    const events = parser.push('data: line one\ndata: line two\n\n');

    expect(events[0]?.data).toBe('line one\nline two');
  });

  it('defaults the event type to "message" when none is given', () => {
    const parser = new SseParser();
    const events = parser.push('data: bare\n\n');

    expect(events[0]?.event).toBe('message');
  });

  it('tracks the last id for Last-Event-ID on reconnect', () => {
    const parser = new SseParser();
    parser.push('id: 17\nevent: progress\ndata: x\n\n');

    expect(parser.getLastEventId()).toBe('17');
  });

  it('carries the last id forward to events that omit one', () => {
    const parser = new SseParser();
    parser.push('id: 5\ndata: a\n\n');
    const events = parser.push('data: b\n\n');

    expect(events[0]?.id).toBe('5');
  });

  it('ignores comment lines used as keep-alives', () => {
    const parser = new SseParser();
    const events = parser.push(': ping\n\n');

    expect(events).toHaveLength(0);
  });

  it('strips exactly one leading space after the colon', () => {
    const parser = new SseParser();
    const events = parser.push('data:  two spaces\n\n');

    // One space is framing; the second belongs to the payload.
    expect(events[0]?.data).toBe(' two spaces');
  });

  it('handles a field with no colon at all', () => {
    const parser = new SseParser();
    const events = parser.push('data\n\n');

    expect(events[0]?.data).toBe('');
  });

  it('normalises CRLF and bare CR line endings', () => {
    const parser = new SseParser();
    const events = parser.push('event: progress\r\ndata: crlf\r\n\r\n');

    expect(events).toHaveLength(1);
    expect(events[0]?.data).toBe('crlf');
  });

  it('parses several events arriving in one chunk', () => {
    const parser = new SseParser();
    const events = parser.push('data: one\n\ndata: two\n\ndata: three\n\n');

    expect(events.map((event) => event.data)).toEqual(['one', 'two', 'three']);
  });

  it('reads a retry interval when the server sends one', () => {
    const parser = new SseParser();
    const events = parser.push('retry: 3000\ndata: x\n\n');

    expect(events[0]?.retry).toBe(3000);
  });

  it('surfaces a payload-free heartbeat as an event so the silence timer can restart', () => {
    const parser = new SseParser();
    const events = parser.push('event: heartbeat\n:keep-alive\n\n');

    // Ровно тот кадр, который шлёт сервер. Проглотить его — значит объявить живой поток мёртвым.
    expect(events).toHaveLength(1);
    expect(events[0]?.event).toBe('heartbeat');
    expect(events[0]?.data).toBe('');
  });

  it('ignores an anonymous frame with neither type nor data', () => {
    const parser = new SseParser();

    expect(parser.push(':just a comment\n\n')).toHaveLength(0);
  });

  it('keeps the event type from leaking into the following event', () => {
    const parser = new SseParser();
    parser.push('event: completed\ndata: first\n\n');
    const events = parser.push('data: second\n\n');

    expect(events[0]?.event).toBe('message');
  });

  it('handles multi-byte UTF-8 payloads', () => {
    const parser = new SseParser();
    const events = parser.push('event: progress\ndata: {"message":"Сбор данных"}\n\n');

    expect(events[0]?.data).toContain('Сбор данных');
  });
});
