/**
 * Which features this build has switched on.
 *
 * Read once and shared, so a switched-off feature never renders a control that answers 404 — and,
 * more importantly, never renders an empty panel. An empty panel reads as "there is no data", which
 * is a statement about the direction under study; "this build does not have that feature" is a
 * statement about the deployment, and an analyst would act differently on each.
 *
 * A flag this build has never heard of is ignored, and a flag the server did not send counts as
 * **on**. Both defaults point the same way: a client that is older than the server must not hide
 * features it does not yet know about, or every release would have to ship both halves at once.
 */
import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useMemo } from 'react';
import type { FeatureFlag } from '@/api/types';
import { api } from '@/api/client';
import { queryKeys } from '@/api/queryKeys';
import type { FeatureKey } from './featureKeys';

const FeatureContext = createContext<ReadonlyMap<string, boolean> | null>(null);

/**
 * Whether a feature is on. Unknown to the server — including while the request is still in flight —
 * counts as on, so the interface never flickers a feature out of existence and back.
 */
// eslint-disable-next-line react-refresh/only-export-components -- the hook belongs with the
// context it reads; splitting them would put a provider and its only accessor in two files.
export function useFeature(key: FeatureKey): boolean {
  const map = useContext(FeatureContext);
  if (!map) return true;
  return map.get(key) ?? true;
}

export function FeatureProvider({ children }: { children: React.ReactNode }): React.ReactElement {
  const features = useQuery({
    queryKey: queryKeys.features,
    queryFn: ({ signal }) => api.listFeatures(signal),
    staleTime: Infinity,
  });

  const map = useMemo(() => {
    const entries = new Map<string, boolean>();
    for (const flag of features.data ?? ([] as FeatureFlag[])) {
      entries.set(flag.key, flag.enabled);
    }
    return entries;
  }, [features.data]);

  return <FeatureContext.Provider value={map}>{children}</FeatureContext.Provider>;
}

/** Renders its children only while the feature is on. */
export function Feature({
  flag,
  children,
}: {
  flag: FeatureKey;
  children: React.ReactNode;
}): React.ReactElement | null {
  return useFeature(flag) ? <>{children}</> : null;
}
