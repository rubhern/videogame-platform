import type { components } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

export type SynchronizationStatus = components["schemas"]["CatalogueSynchronizationStatus"];
export type CurrentRun = components["schemas"]["CurrentSynchronizationRun"];
export type FinishedRun = components["schemas"]["FinishedSynchronizationRun"];
export type SynchronizationCoverage = components["schemas"]["CatalogueSynchronizationCoverage"];
export type CoveragePeriod = components["schemas"]["CoveragePeriod"];
export type SynchronizationWindow = components["schemas"]["CatalogueSynchronizationWindow"];
export type AcceptedRun = components["schemas"]["AcceptedSynchronizationRun"];
export type StopRequest = components["schemas"]["SynchronizationStopRequest"];

/** Session-scoped: logout removes every `["me"]` query, so operator evidence never outlives it. */
export const ADMINISTRATION_KEY = ["me", "catalogue-administration"] as const;
export const SYNCHRONIZATION_STATUS_KEY = [...ADMINISTRATION_KEY, "status"] as const;

/**
 * A failed administration read or request; `code` is the stable Problem code when the server sent
 * one, and `pointer` the first violation's pointer, naming the field to correct.
 */
export class AdministrationError extends Error {
  constructor(
    readonly code: components["schemas"]["ProblemCode"] | null,
    readonly correlationId: string | null,
    readonly pointer: string | null = null,
  ) {
    super("Catalogue administration could not be read.");
  }
}

type ProblemBody = Partial<Pick<components["schemas"]["Problem"], "code" | "correlationId" | "violations">>;

function failure(response: Response, error: ProblemBody | undefined) {
  return new AdministrationError(
    error?.code ?? (response.status === 401 ? "AUTHENTICATION_REQUIRED" : response.status === 403 ? "ACCESS_DENIED" : null),
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
    error?.violations?.[0]?.pointer ?? null,
  );
}

/** The run in flight, if any, and the bounded recent run history. */
export async function getSynchronizationStatus(signal?: AbortSignal,
  client: ProductApiClient = productApiClient): Promise<SynchronizationStatus> {
  const { data, error, response } = await client.GET("/admin/catalogue/synchronization", {
    cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/** Covered and never-covered periods of an inclusive ISO date range, bounded by the server. */
export async function getSynchronizationCoverage(range: { from: string; to: string }, signal?: AbortSignal,
  client: ProductApiClient = productApiClient): Promise<SynchronizationCoverage> {
  const { data, error, response } = await client.GET("/admin/catalogue/synchronization-coverage", {
    params: { query: range }, cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/**
 * Requests one bounded synchronization. The answer means the run was accepted, never that it
 * finished: its progress and result are read from the synchronization status.
 */
export async function requestSynchronization(window: SynchronizationWindow, csrfToken: string,
  client: ProductApiClient = productApiClient): Promise<AcceptedRun> {
  const { data, error, response } = await client.POST("/admin/catalogue/synchronization", {
    params: { header: { "X-CSRF-Token": csrfToken } }, body: window, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/**
 * Asks the named run to stop at its next safe point. The answer means the request is recorded,
 * never that the run stopped: its result is read from the synchronization status.
 */
export async function requestRunStop(runId: string, csrfToken: string,
  client: ProductApiClient = productApiClient): Promise<StopRequest> {
  const { data, error, response } = await client.POST("/admin/catalogue/synchronization-runs/{runId}/stop", {
    params: { path: { runId }, header: { "X-CSRF-Token": csrfToken } }, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/** Abandons the named run when the server confirms its worker stopped giving heartbeats. */
export async function abandonRun(runId: string, csrfToken: string,
  client: ProductApiClient = productApiClient): Promise<FinishedRun> {
  const { data, error, response } = await client.POST("/admin/catalogue/synchronization-runs/{runId}/abandon", {
    params: { path: { runId }, header: { "X-CSRF-Token": csrfToken } }, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export type HistoricalStatus = components["schemas"]["HistoricalSynchronizationStatus"];
export type HistoricalTarget = components["schemas"]["HistoricalSynchronizationTarget"];
export const HISTORICAL_STATUS_KEY = [...ADMINISTRATION_KEY, "historical"] as const;

export async function getHistoricalStatus(signal?: AbortSignal,
  client: ProductApiClient = productApiClient): Promise<HistoricalStatus> {
  const { data, error, response } = await client.GET("/admin/catalogue/historical-synchronization", {
    cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export async function startHistoricalSynchronization(target: HistoricalTarget, csrfToken: string,
  client: ProductApiClient = productApiClient): Promise<HistoricalStatus> {
  const { data, error, response } = await client.POST("/admin/catalogue/historical-synchronization", {
    params: { header: { "X-CSRF-Token": csrfToken } }, body: target, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export async function controlHistoricalSynchronization(action: "stop" | "resume", backfillId: string,
  csrfToken: string, client: ProductApiClient = productApiClient): Promise<HistoricalStatus> {
  const path = action === "stop" ? "/admin/catalogue/historical-synchronization/{backfillId}/stop"
    : "/admin/catalogue/historical-synchronization/{backfillId}/resume";
  const { data, error, response } = await client.POST(path, {
    params: { path: { backfillId }, header: { "X-CSRF-Token": csrfToken } }, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export type TranslationCoverage = components["schemas"]["TranslationCoverage"];
export type TranslationExecution = components["schemas"]["TranslationExecution"];
export type TranslationCommand = components["schemas"]["TranslationExecutionCommand"];
export const TRANSLATION_COVERAGE_KEY = [...ADMINISTRATION_KEY, "translation-coverage"] as const;
export const TRANSLATION_EXECUTION_KEY = [...ADMINISTRATION_KEY, "translation-execution"] as const;

export async function getTranslationCoverage(signal: AbortSignal): Promise<TranslationCoverage> {
  const { data, error, response } = await productApiClient.GET("/admin/catalogue/translations/coverage", {
    cache: "no-store", signal,
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export async function getTranslationExecution(signal: AbortSignal): Promise<TranslationExecution> {
  const { data, error, response } = await productApiClient.GET("/admin/catalogue/translations/execution", {
    cache: "no-store", signal,
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export async function controlTranslationExecution(command: TranslationCommand, csrfToken: string): Promise<TranslationExecution> {
  const { data, error, response } = await productApiClient.POST("/admin/catalogue/translations/execution", {
    params: { header: { "X-CSRF-Token": csrfToken } }, body: command, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export type EditorialAttentionOverview = components["schemas"]["EditorialAttentionOverview"];
export type EditorialAttentionPreview = components["schemas"]["EditorialAttentionPreview"];
export type EditorialAttentionPreviewEntry = components["schemas"]["EditorialAttentionPreviewEntry"];
export type EditorialAttentionWarning = components["schemas"]["EditorialAttentionWarning"];
export type EditorialAttentionListDetail = components["schemas"]["EditorialAttentionListDetail"];
export type EditorialAttentionListSummary = components["schemas"]["EditorialAttentionListSummary"];
export type EditorialAttentionAssignment = components["schemas"]["EditorialAttentionAssignment"];
export const EDITORIAL_ATTENTION_KEY = [...ADMINISTRATION_KEY, "editorial-attention"] as const;

/** The prompt for a language model and the loaded editorial lists, latest range first. */
export async function getEditorialAttention(signal: AbortSignal): Promise<EditorialAttentionOverview> {
  const { data, error, response } = await productApiClient.GET("/admin/catalogue/editorial-attention", {
    cache: "no-store", signal,
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/** How the file would load: each entry's catalogue match and warnings. Nothing is stored. */
export async function previewEditorialAttention(content: string, csrfToken: string): Promise<EditorialAttentionPreview> {
  const { data, error, response } = await productApiClient.POST("/admin/catalogue/editorial-attention/previews", {
    params: { header: { "X-CSRF-Token": csrfToken } }, body: { content }, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/** Loads the file as its range's list, with the operator's assignments by rank. */
export async function loadEditorialAttention(content: string, assignments: EditorialAttentionAssignment[],
  csrfToken: string): Promise<EditorialAttentionListDetail> {
  const { data, error, response } = await productApiClient.POST("/admin/catalogue/editorial-attention/lists", {
    params: { header: { "X-CSRF-Token": csrfToken } }, body: { content, assignments }, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export async function getEditorialAttentionList(listId: string, signal: AbortSignal): Promise<EditorialAttentionListDetail> {
  const { data, error, response } = await productApiClient.GET("/admin/catalogue/editorial-attention/lists/{listId}", {
    params: { path: { listId } }, cache: "no-store", signal,
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

export async function deleteEditorialAttentionList(listId: string, csrfToken: string): Promise<void> {
  const { error, response } = await productApiClient.DELETE("/admin/catalogue/editorial-attention/lists/{listId}", {
    params: { path: { listId }, header: { "X-CSRF-Token": csrfToken } }, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (response.ok) return;
  throw failure(response, error);
}

export type ProductSettingsOverview = components["schemas"]["ProductSettingsOverview"];
export type ProductSettingValue = components["schemas"]["ProductSettingValue"];
export type ProductSettingKey = components["schemas"]["ProductSettingKey"];
export type ProductSettingChange = components["schemas"]["ProductSettingChange"];
export type ProductSettingsCoverage = components["schemas"]["ProductSettingsCoverage"];
export type ProductSettingUpdate = components["schemas"]["ProductSettingUpdateChange"];
export const PRODUCT_SETTINGS_KEY = [...ADMINISTRATION_KEY, "product-settings"] as const;

/** Every editable product setting with its default, range and origin, coverage and recent changes. */
export async function getProductSettings(signal?: AbortSignal,
  client: ProductApiClient = productApiClient): Promise<ProductSettingsOverview> {
  const { data, error, response } = await client.GET("/admin/catalogue/product-settings", {
    cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/**
 * Applies every change prepared from `revision`, or none. A change without `value` restores the
 * default; another change made meanwhile is refused with `PRODUCT_SETTINGS_REVISION_CONFLICT`.
 */
export async function updateProductSettings(revision: number, changes: ProductSettingUpdate[], csrfToken: string,
  client: ProductApiClient = productApiClient): Promise<ProductSettingsOverview> {
  const { data, error, response } = await client.PUT("/admin/catalogue/product-settings", {
    params: { header: { "X-CSRF-Token": csrfToken } }, body: { revision, changes }, cache: "no-store",
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}
