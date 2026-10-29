import type { components } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

export type PrivateProfile = components["schemas"]["PrivateProfile"];
export type DeletionStatus = components["schemas"]["AccountDeletionStatus"];

export class ProfileError extends Error {
  constructor(readonly status: number, readonly code?: string) { super("Account operation failed"); }
}

export async function getProfile(signal?: AbortSignal, client: ProductApiClient = productApiClient): Promise<PrivateProfile> {
  const { data, error, response } = await client.GET("/me/profile", { ...(signal ? { signal } : {}), cache: "no-store" });
  if (!response.ok || !data) throw new ProfileError(response.status, error?.code);
  return data;
}

export async function exportData(client: ProductApiClient = productApiClient): Promise<Blob> {
  const { data, response } = await client.GET("/me/data-export", { parseAs: "blob", cache: "no-store" });
  if (!response.ok || !data) throw new ProfileError(response.status);
  return data;
}

export function saveExport(blob: Blob) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "gameometro-datos.json";
  link.click();
  URL.revokeObjectURL(url);
}

export async function deleteAccount(csrfToken: string, client: ProductApiClient = productApiClient): Promise<DeletionStatus> {
  const { data, error, response } = await client.POST("/me/account-deletion", {
    params: { header: { "X-CSRF-Token": csrfToken } }, body: { confirmation: "ELIMINAR" },
  });
  if (response.status !== 202 || !data) throw new ProfileError(response.status, error?.code);
  return data;
}

export async function deletionStatus(signal?: AbortSignal, client: ProductApiClient = productApiClient): Promise<DeletionStatus> {
  const { data, error, response } = await client.GET("/account-deletion", { ...(signal ? { signal } : {}), cache: "no-store" });
  if (!response.ok || !data) throw new ProfileError(response.status, error?.code);
  return data;
}
