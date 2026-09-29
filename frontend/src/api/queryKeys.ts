/**
 * Central query-key factory.
 *
 * Keeping every key in one place makes invalidation auditable: when a mutation
 * needs to refresh "the report and its trend", both keys are visible here rather
 * than being re-typed at each call site and drifting apart.
 */
import type { ResearchStatus } from './types';

export const queryKeys = {
  features: ['features'] as const,

  researchRequests: (page: number, size: number, status?: ResearchStatus, onlyMine = false) =>
    ['research-requests', { page, size, status: status ?? null, onlyMine }] as const,
  researchRequest: (requestId: string) => ['research-requests', requestId] as const,

  report: (reportId: string) => ['reports', reportId] as const,
  reportTrend: (reportId: string, trendKey: string) =>
    ['reports', reportId, 'trends', trendKey] as const,
  reportDelta: (reportId: string) => ['reports', reportId, 'delta'] as const,
  reportExplanation: (reportId: string, terms: readonly string[]) =>
    ['reports', reportId, 'explain', [...terms].sort()] as const,

  savedDomains: ['saved-domains'] as const,
  radarDigest: ['saved-domains', 'digest'] as const,
  directionOverlap: ['saved-domains', 'overlap'] as const,

  topicSearch: (q: string) => ['topics', 'search', q] as const,
  knownDirections: ['directions', 'known'] as const,
  directionResolution: (query: string) => ['directions', 'resolve', query] as const,

  sources: ['sources'] as const,
  openAlexQuota: ['sources', 'openalex', 'quota'] as const,
  ingestionRuns: (page: number, size: number, sourceId?: string) =>
    ['ingestion-runs', { page, size, sourceId: sourceId ?? null }] as const,

  quota: ['research-requests', 'quota'] as const,
  unrecognizedDirections: (limit: number) => ['admin', 'unrecognized-directions', limit] as const,
} as const;
