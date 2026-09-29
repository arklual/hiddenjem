/**
 * Keys the client may ask about — mirrors the server's registry.
 *
 * Kept apart from the components so the provider module exports components only; the compiler
 * then refuses a typo in a flag name, which a bare string would have accepted silently.
 */
export type FeatureKey =
  | 'radar'
  | 'direction-portrait'
  | 'report-delta'
  | 'term-trace'
  | 'report-export'
  | 'topic-search'
  | 'trend-feedback'
  | 'direction-overlap'
  | 'saved-domains';
