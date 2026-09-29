/// <reference types="vite/client" />

/**
 * Typed build-time environment.
 *
 * Declaring these explicitly (rather than relying on Vite's `[key: string]: any`
 * index signature) keeps `no-unsafe-assignment` satisfied and documents every
 * variable the app reads — see `.env.example`.
 */
interface ImportMetaEnv {
  /**
   * Origin the API is served from. Empty in both dev and prod: the Vite dev
   * server and the production nginx both proxy `/api` to the gateway.
   */
  readonly VITE_API_BASE_URL?: string;
  /** Dev-only proxy target, read by `vite.config.ts` (never by app code). */
  readonly VITE_API_PROXY_TARGET?: string;
  /** Interval in ms for the polling fallback when SSE is unavailable. */
  readonly VITE_PROGRESS_POLL_INTERVAL_MS?: string;
  /** Silence window in ms before the SSE stream is judged dead. */
  readonly VITE_SSE_STALE_TIMEOUT_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
