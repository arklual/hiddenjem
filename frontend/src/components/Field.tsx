/**
 * Поля формы на основе shadcn/ui.
 *
 * Оформление сменилось, связи — нет. Каждое поле по-прежнему связывает подпись, пояснение и ошибку
 * настоящими `id`, `aria-describedby`, `aria-invalid` и `aria-errormessage`, поэтому читающий с
 * экрана слышит ошибку вместе с полем, а не отдельной репликой где-то на странице (FR-06.6).
 *
 * Отличие от эталонного shadcn намеренное: там `Label`, `Input` и `FormMessage` расставляет автор
 * формы, здесь их расставляет сам компонент. Причина простая — связи, которые нужно не забыть,
 * забываются; связи, которые нельзя не сделать, работают всегда.
 */
import {
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/lib/cn';
import { useT } from '@/lib/i18n/useT';
import { Icon } from './Icon';

/** Общий вид поля ввода: одна строка, чтобы input, textarea и select не разъезжались. */
const controlClass = cn(
  'flex h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs',
  'transition-[color,box-shadow] outline-none placeholder:text-muted-foreground',
  'focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50',
  'disabled:cursor-not-allowed disabled:opacity-50',
  'aria-invalid:border-destructive aria-invalid:ring-destructive/25',
);

interface FieldShellProps {
  id: string;
  label?: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  required?: boolean;
  showOptional?: boolean;
  children: ReactNode;
  className?: string;
}

/** Ids derived from the control id, so every consumer links them identically. */
function describedByIds(
  id: string,
  hint: unknown,
  error: unknown,
): { hintId?: string; errorId?: string; describedBy?: string } {
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  return { hintId, errorId, describedBy };
}

function FieldShell({
  id,
  label,
  hint,
  error,
  required,
  showOptional,
  children,
  className,
}: FieldShellProps): React.ReactElement {
  const t = useT();
  const { hintId, errorId } = describedByIds(id, hint, error);

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      {label ? (
        <div className="flex items-baseline justify-between gap-2">
          <label
            className="text-sm font-medium leading-none select-none"
            htmlFor={id}
          >
            {label}
            {required ? (
              <span className="ml-0.5 text-destructive" aria-hidden="true">
                *
              </span>
            ) : null}
          </label>
          {showOptional && !required ? (
            <span className="text-xs text-muted-foreground">{t('common.optional')}</span>
          ) : null}
        </div>
      ) : null}

      {children}

      {hint ? (
        <p className="text-xs text-muted-foreground" id={hintId}>
          {hint}
        </p>
      ) : null}

      {error ? (
        <p
          className="flex items-start gap-1.5 text-xs font-medium text-destructive"
          id={errorId}
          role="alert"
        >
          <Icon name="alert" size={13} className="mt-px shrink-0" />
          <span>{error}</span>
        </p>
      ) : null}
    </div>
  );
}

/* ── Input ───────────────────────────────────────────────────── */

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label?: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  id?: string;
  showOptional?: boolean;
  fieldClassName?: string;
}

export function Input({
  label,
  hint,
  error,
  id,
  required,
  showOptional,
  className,
  fieldClassName,
  ...rest
}: InputProps): React.ReactElement {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const { describedBy, errorId } = describedByIds(inputId, hint, error);

  return (
    <FieldShell
      id={inputId}
      label={label}
      hint={hint}
      error={error}
      required={required}
      showOptional={showOptional}
      className={fieldClassName}
    >
      <input
        id={inputId}
        className={cn(controlClass, className)}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-errormessage={errorId}
        aria-describedby={describedBy}
        {...rest}
      />
    </FieldShell>
  );
}

/* ── Textarea ────────────────────────────────────────────────── */

export interface TextareaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> {
  label?: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  id?: string;
  showOptional?: boolean;
}

export function Textarea({
  label,
  hint,
  error,
  id,
  required,
  showOptional,
  className,
  ...rest
}: TextareaProps): React.ReactElement {
  const generatedId = useId();
  const areaId = id ?? generatedId;
  const { describedBy, errorId } = describedByIds(areaId, hint, error);

  return (
    <FieldShell
      id={areaId}
      label={label}
      hint={hint}
      error={error}
      required={required}
      showOptional={showOptional}
    >
      <textarea
        id={areaId}
        className={cn(controlClass, 'field-sizing-content min-h-16 py-2', className)}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-errormessage={errorId}
        aria-describedby={describedBy}
        {...rest}
      />
    </FieldShell>
  );
}

/* ── Select ──────────────────────────────────────────────────── */

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label?: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  id?: string;
  options: readonly SelectOption[];
  showOptional?: boolean;
  /** Оформление обёртки поля — ширина и место в строке. Симметрично {@link InputProps}. */
  fieldClassName?: string;
}

export function Select({
  label,
  hint,
  error,
  id,
  options,
  required,
  showOptional,
  className,
  fieldClassName,
  ...rest
}: SelectProps): React.ReactElement {
  const generatedId = useId();
  const selectId = id ?? generatedId;
  const { describedBy, errorId } = describedByIds(selectId, hint, error);

  return (
    <FieldShell
      id={selectId}
      label={label}
      hint={hint}
      error={error}
      required={required}
      showOptional={showOptional}
      className={fieldClassName}
    >
      <span className="relative block">
        <select
          id={selectId}
          className={cn(controlClass, 'appearance-none pr-9', className)}
          required={required}
          aria-invalid={error ? true : undefined}
          aria-errormessage={errorId}
          aria-describedby={describedBy}
          {...rest}
        >
          {options.map((option) => (
            <option key={option.value} value={option.value} disabled={option.disabled}>
              {option.label}
            </option>
          ))}
        </select>
        <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center text-muted-foreground">
          <Icon name="chevronDown" size={14} />
        </span>
      </span>
    </FieldShell>
  );
}

/* ── Checkbox ────────────────────────────────────────────────── */

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'id'> {
  label: ReactNode;
  hint?: ReactNode;
  id?: string;
}

export function Checkbox({
  label,
  hint,
  id,
  className,
  ...rest
}: CheckboxProps): React.ReactElement {
  const generatedId = useId();
  const boxId = id ?? generatedId;
  const hintId = hint ? `${boxId}-hint` : undefined;

  return (
    <label
      className={cn('flex cursor-pointer items-start gap-2.5 text-sm select-none', className)}
      htmlFor={boxId}
    >
      <input
        id={boxId}
        type="checkbox"
        className={cn(
          'mt-0.5 size-4 shrink-0 cursor-pointer rounded-[4px] border border-input accent-primary',
          'outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
          'disabled:cursor-not-allowed disabled:opacity-50',
        )}
        aria-describedby={hintId}
        {...rest}
      />
      <span className="flex flex-col gap-0.5">
        {label}
        {hint ? (
          <span className="text-xs text-muted-foreground" id={hintId}>
            {hint}
          </span>
        ) : null}
      </span>
    </label>
  );
}
