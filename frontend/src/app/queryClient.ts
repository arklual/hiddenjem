/**
 * TanStack Query configuration.
 *
 * Defaults are tuned for an analyst tool rather than a feed:
 *  - a generated report is immutable (BRULE-5), so refetching on window focus
 *    would be pure noise and pure load;
 *  - a 4xx is never retried — the HTTP client has already attempted a silent
 *    refresh and replay before any error reaches Query, so a second identical
 *    request cannot produce a different answer;
 *  - 429 is not retried either: the UI shows `Retry-After` and lets the analyst
 *    decide, rather than quietly eating into the hourly quota.
 */
import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/problem';

const MAX_QUERY_RETRIES = 2;

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
        return failureCount < MAX_QUERY_RETRIES;
      },
    },
    mutations: {
      retry: false,
    },
  },
});
