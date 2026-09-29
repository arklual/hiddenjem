import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/cn';
import { Icon, type IconName } from './Icon';

/**
 * Кнопка на основе shadcn/ui.
 *
 * Интерфейс оставлен прежним — `variant`, `size`, `icon`, `loading`, `block`, — потому что менялось
 * оформление, а не поведение: полсотни мест вызова не должны переписываться из-за смены слоя стилей.
 * Внутри вместо CSS-модуля вариантный класс (cva) и `Slot` из Radix: `asChild` позволяет отрисовать
 * ссылку с видом кнопки, не вкладывая одно в другое — вложенные `<a>` внутри `<button>` ломают и
 * клавиатуру, и чтение с экрана.
 */
const buttonVariants = cva(
  cn(
    'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium',
    'transition-colors outline-none disabled:pointer-events-none disabled:opacity-50',
    "focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:border-ring [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4 shrink-0",
  ),
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-foreground shadow-xs hover:bg-primary/90',
        secondary:
          'border border-input bg-background shadow-xs hover:bg-accent hover:text-accent-foreground',
        ghost: 'hover:bg-accent hover:text-accent-foreground',
        danger:
          'bg-destructive text-destructive-foreground shadow-xs hover:bg-destructive/90 focus-visible:ring-destructive/30',
        link: 'text-foreground underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-8 gap-1.5 px-3 text-xs has-[>svg]:px-2.5',
        md: 'h-9 px-4 py-2 has-[>svg]:px-3',
        lg: 'h-10 px-6 has-[>svg]:px-4',
      },
      block: { true: 'w-full', false: '' },
    },
    defaultVariants: { variant: 'secondary', size: 'md', block: false },
  },
);

export type ButtonVariant = NonNullable<VariantProps<typeof buttonVariants>['variant']>;
export type ButtonSize = NonNullable<VariantProps<typeof buttonVariants>['size']>;

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Значок перед подписью. Декоративный — смысл несёт подпись. */
  icon?: IconName;
  iconAfter?: IconName;
  /** Показывает вращение и запрещает нажатие; подпись остаётся на месте. */
  loading?: boolean;
  block?: boolean;
  /** Отрисовать как переданный дочерний элемент (обычно `<Link>`), сохранив вид кнопки. */
  asChild?: boolean;
  children?: ReactNode;
}

export function Button({
  variant = 'secondary',
  size = 'md',
  icon,
  iconAfter,
  loading = false,
  block = false,
  asChild = false,
  disabled,
  className,
  children,
  type = 'button',
  ...rest
}: ButtonProps): React.ReactElement {
  const Component = asChild ? Slot : 'button';
  const iconSize = size === 'sm' ? 14 : 16;

  return (
    <Component
      {...(asChild ? {} : { type, disabled: disabled ?? loading })}
      className={cn(buttonVariants({ variant, size, block }), className)}
      // Вспомогательным технологиям это говорит «занята», а не «не отвечает».
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? (
        <span
          className="size-4 animate-spin rounded-full border-2 border-current border-t-transparent"
          aria-hidden="true"
        />
      ) : icon ? (
        <Icon name={icon} size={iconSize} />
      ) : null}
      {children}
      {iconAfter && !loading ? <Icon name={iconAfter} size={iconSize} /> : null}
    </Component>
  );
}

export { buttonVariants };
