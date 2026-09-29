/**
 * Last line of defence for a render-time crash.
 *
 * A thrown render is a bug, not a user error, so this offers a reload rather
 * than pretending a retry will help — and keeps the stack behind a disclosure
 * so it is available for a bug report without shouting at the analyst.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
  componentStack: string | null;
}

export class RootErrorBoundary extends Component<Props, State> {
  override state: State = { error: null, componentStack: null };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Surfaced in the browser console for local debugging; production
    // observability comes from the gateway's traces, not from the SPA.
    console.error('Необработанная ошибка интерфейса', error, info.componentStack);
    this.setState({ componentStack: info.componentStack ?? null });
  }

  override render(): ReactNode {
    const { error, componentStack } = this.state;
    if (!error) return this.props.children;

    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-3 bg-muted/30 p-8 text-center" role="alert">
        <span className="flex size-13 items-center justify-center rounded-full bg-destructive/10 text-destructive">
          <Icon name="alert" size={24} />
        </span>
        <h1 className="text-xl font-semibold">Что-то пошло не так</h1>
        <p className="max-w-[52ch] text-base leading-relaxed text-muted-foreground">
          Интерфейс не смог отобразить эту страницу. Данные на сервере не пострадали.
        </p>
        <div className="mt-2">
          <Button variant="primary" icon="refresh" onClick={() => window.location.reload()}>
            Перезагрузить страницу
          </Button>
        </div>
        <details className="mt-4 w-full max-w-[70ch] text-start">
          <summary className="cursor-pointer text-xs text-muted-foreground">Технические подробности</summary>
          <pre className="mt-2 max-h-80 overflow-x-auto rounded-md border border-border bg-muted/50 p-3 font-mono text-xs leading-normal whitespace-pre-wrap text-muted-foreground">
            {error.message}
            {componentStack ?? ''}
          </pre>
        </details>
      </div>
    );
  }
}
