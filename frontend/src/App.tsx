/**
 * Provider composition.
 *
 * Order matters: every data hook needs Query above it. Провайдера сессии нет — входа в
 * системе нет, и ни один экран не зависит от того, кто его открыл.
 */
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { ToastProvider } from '@/components/toast/ToastProvider';
import { FeatureProvider } from '@/lib/features';
import { queryClient } from './app/queryClient';
import { AppRoutes } from './app/routes';
import { RootErrorBoundary } from './app/RootErrorBoundary';

export function App(): React.ReactElement {
  return (
    <RootErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter
          // Opt in to the v7 behaviours now, so the upgrade is a version bump
          // rather than a behavioural change — and dev logs stay clean.
          future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
        >
          <ToastProvider>
            {/* Outside the routes: the registry decides what exists at all, including which
                navigation entries appear, so it must be resolved before anything renders. */}
            <FeatureProvider>
              <AppRoutes />
            </FeatureProvider>
          </ToastProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </RootErrorBoundary>
  );
}
