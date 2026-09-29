import type { HTMLAttributes, ReactNode } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/cn';
import { Icon, type IconName } from './Icon';

/**
 * Метка состояния на основе shadcn/ui.
 *
 * Тонов шесть, а не два, и это требование предметной области: «неполное покрытие», «из кэша»,
 * «направление не распознано» и «высокая уверенность» обязаны читаться как разные вещи с одного
 * взгляда. Цвет при этом никогда не единственный носитель смысла — рядом всегда текст, а часто и
 * значок (WCAG 1.4.1).
 *
 * `dotColor` стоит особняком: он раскрашивает точку произвольным цветом ряда данных, когда метка
 * обозначает принадлежность к категории на графике, а не состояние.
 */
const badgeVariants = cva(
  cn(
    'inline-flex w-fit items-center justify-center gap-1.5 whitespace-nowrap rounded-md border',
    'px-2 py-0.5 text-xs font-medium leading-none transition-colors',
    "[&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-3 shrink-0",
  ),
  {
    variants: {
      tone: {
        neutral: 'border-transparent bg-secondary text-secondary-foreground',
        accent: 'border-transparent bg-primary text-primary-foreground',
        good: 'border-transparent bg-success text-success-foreground',
        warning: 'border-transparent bg-warning text-warning-foreground',
        serious: 'border-transparent bg-destructive/85 text-destructive-foreground',
        critical: 'border-transparent bg-destructive text-destructive-foreground',
      },
      size: { sm: '', lg: 'px-2.5 py-1 text-sm' },
      outline: { true: '', false: '' },
      mono: { true: 'font-mono tabular-nums', false: '' },
    },
    compoundVariants: [
      { outline: true, tone: 'neutral', class: 'border-border bg-transparent text-foreground' },
      { outline: true, tone: 'accent', class: 'border-primary bg-transparent text-foreground' },
      { outline: true, tone: 'good', class: 'border-success bg-transparent text-foreground' },
      { outline: true, tone: 'warning', class: 'border-warning bg-transparent text-foreground' },
      { outline: true, tone: 'serious', class: 'border-destructive bg-transparent text-foreground' },
      {
        outline: true,
        tone: 'critical',
        class: 'border-destructive bg-transparent text-foreground',
      },
    ],
    defaultVariants: { tone: 'neutral', size: 'sm', outline: false, mono: false },
  },
);

export type BadgeTone = NonNullable<VariantProps<typeof badgeVariants>['tone']>;

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  /** Цвет точки принадлежности — для категорий и порядковых шкал. */
  dotColor?: string;
  icon?: IconName;
  size?: 'sm' | 'lg';
  outline?: boolean;
  mono?: boolean;
  children: ReactNode;
}

export function Badge({
  tone = 'neutral',
  dotColor,
  icon,
  size = 'sm',
  outline = false,
  mono = false,
  className,
  children,
  style,
  ...rest
}: BadgeProps): React.ReactElement {
  return (
    <span
      className={cn(badgeVariants({ tone, size, outline, mono }), className)}
      style={dotColor ? { ...style, ['--badge-dot' as string]: dotColor } : style}
      {...rest}
    >
      {dotColor ? (
        <span
          className="size-2 shrink-0 rounded-full bg-[var(--badge-dot)]"
          aria-hidden="true"
        />
      ) : null}
      {icon ? <Icon name={icon} size={12} /> : null}
      {children}
    </span>
  );
}

export { badgeVariants };
