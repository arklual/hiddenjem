/**
 * Записка для комитета — Markdown из тем, которые аналитик отметил «В записку».
 *
 * Собирается из снимков, сделанных в момент отметки, поэтому в ней ровно то, что аналитик видел:
 * название, место, балл, стадия, надёжность с оговорками, суть и ключевые источники со ссылками.
 * Её отправляют тем, кто отчёт не открывал, — значит, каждая оговорка обязана быть в тексте.
 */
import { RELIABILITY_LABEL } from '@/features/report/topicModel';
import type { TopicSnapshot } from '@/lib/localState';

function topicMarkdown(item: TopicSnapshot, index: number): string {
  const lines: string[] = [];
  lines.push(
    `## ${index}. ${item.title}${item.original !== item.title ? ` (${item.original})` : ''}`,
    '',
  );
  if (item.statement) lines.push(`**Тренд:** ${item.statement}`, '');
  lines.push(`- Направление: ${item.query}; место ${item.rank}`);
  lines.push(
    `- Балл зарождения: ${item.score.toLocaleString('ru-RU', { maximumFractionDigits: 1 })} из 100; стадия: ${item.stage.toLowerCase()}`,
  );
  lines.push(`- Надёжность: ${RELIABILITY_LABEL[item.reliability].toLowerCase()}`);
  for (const flag of item.flags) lines.push(`  - ${flag}`);
  lines.push(`- Документов по теме: ${item.documents}`);
  lines.push(`- Что это: ${item.definition}`);
  if (item.problem) lines.push(`- Какую проблему решает: ${item.problem}`);
  if (item.benefit) lines.push(`- Какое преимущество даёт: ${item.benefit}`);
  if (item.sources.length > 0) {
    lines.push('- Ключевые источники:');
    item.sources.forEach((source, position) => {
      const meta = [source.organization, source.publishedOn].filter(Boolean).join(', ');
      lines.push(`  ${position + 1}. ${source.title}${meta ? ` — ${meta}` : ''}. ${source.url}`);
    });
  }
  lines.push('');
  return lines.join('\n');
}

export function briefingMarkdown(items: readonly TopicSnapshot[], now = new Date()): string {
  const date = now.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
  const body = items.map((item, index) => topicMarkdown(item, index + 1)).join('\n');
  return `# Записка для комитета\n\nСформировано в Hiddenjem · ${date}\n\n${body}`;
}

export function downloadFile(filename: string, content: Blob | string, type = 'text/plain'): void {
  const blob = typeof content === 'string' ? new Blob([content], { type }) : content;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1500);
}
