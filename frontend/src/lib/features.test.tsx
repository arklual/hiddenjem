import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FeatureFlag as FeatureFlagView } from '@/api/types';
import { api } from '@/api/client';
import { Feature, FeatureProvider } from './features';

function renderWith(flags: FeatureFlagView[]): void {
  vi.spyOn(api, 'listFeatures').mockResolvedValue(flags);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <FeatureProvider>
        <Feature flag="report-delta">
          <p>панель дельты</p>
        </Feature>
      </FeatureProvider>
    </QueryClientProvider>,
  );
}

function flag(key: string, enabled: boolean): FeatureFlagView {
  return { key, title: key, enabled };
}

describe('Feature', () => {
  beforeEach(() => vi.restoreAllMocks());
  afterEach(() => vi.restoreAllMocks());

  it('renders the feature when the build has it on', async () => {
    renderWith([flag('report-delta', true)]);

    await waitFor(() => expect(screen.getByText('панель дельты')).toBeInTheDocument());
  });

  it('removes the feature entirely rather than rendering it empty', async () => {
    // An empty panel reads as "there is no data" — a claim about the direction under study. "This
    // build does not have that feature" is a claim about the deployment, and the two demand
    // different reactions from an analyst.
    renderWith([flag('report-delta', false)]);

    // Waiting on the call alone would assert before the answer arrived: the request fires on mount,
    // so the assertion would pass even if the flag were ignored entirely.
    await waitFor(() => expect(screen.queryByText('панель дельты')).not.toBeInTheDocument());
  });

  it('treats a flag the server did not send as on', async () => {
    // A client older than the server must not hide features it has not heard of, or every release
    // would have to ship both halves at once.
    renderWith([flag('radar', true)]);

    await waitFor(() => expect(screen.getByText('панель дельты')).toBeInTheDocument());
  });

  it('shows the feature while the registry is still loading', () => {
    // Otherwise the interface flickers a feature out of existence and back on every page load.
    vi.spyOn(api, 'listFeatures').mockImplementation(() => new Promise(() => []));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <FeatureProvider>
          <Feature flag="report-delta">
            <p>панель дельты</p>
          </Feature>
        </FeatureProvider>
      </QueryClientProvider>,
    );

    expect(screen.getByText('панель дельты')).toBeInTheDocument();
  });

  it('shows the feature when the registry could not be read at all', async () => {
    // A failed request must not amputate the product: an unreachable registry is an infrastructure
    // problem, and hiding half the interface would turn it into a product one.
    vi.spyOn(api, 'listFeatures').mockRejectedValue(new Error('нет связи'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <FeatureProvider>
          <Feature flag="report-delta">
            <p>панель дельты</p>
          </Feature>
        </FeatureProvider>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(api.listFeatures).toHaveBeenCalled());
    expect(screen.getByText('панель дельты')).toBeInTheDocument();
  });
});
