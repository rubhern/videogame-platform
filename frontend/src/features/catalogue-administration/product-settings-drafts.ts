import type { ProductSettingKey, ProductSettingUpdate, ProductSettingValue } from "./administration-api";

const number = new Intl.NumberFormat("es-ES", { maximumFractionDigits: 4 });

/** `null` when the draft fits the setting; otherwise the reason in words. */
export function draftError(setting: ProductSettingValue, draft: string): string | null {
  const text = draft.trim().replace(",", ".");
  if (text === "") return "Indica un valor.";
  const value = Number(text);
  if (!Number.isFinite(value)) return "Indica un número.";
  if (setting.kind === "integer" && !Number.isInteger(value)) return "Indica un número entero.";
  if (setting.kind === "decimal" && !/^-?\d+(\.\d{1,4})?$/.test(text)) return "Usa como máximo cuatro decimales.";
  if (value < setting.minimum || value > setting.maximum) {
    return `Debe estar entre ${number.format(setting.minimum)} y ${number.format(setting.maximum)}.`;
  }
  return null;
}

/** The changes the operator prepared: a new value, or `null` to restore the default. */
export type Drafts = Partial<Record<ProductSettingKey, string | null>>;

export function changesOf(settings: ProductSettingValue[], drafts: Drafts): ProductSettingUpdate[] {
  const changes: ProductSettingUpdate[] = [];
  for (const setting of settings) {
    if (!(setting.key in drafts)) continue;
    const draft = drafts[setting.key];
    if (draft === null) {
      changes.push({ key: setting.key });
    } else if (draft !== undefined && Number(draft.trim().replace(",", ".")) !== setting.value) {
      changes.push({ key: setting.key, value: Number(draft.trim().replace(",", ".")) });
    }
  }
  return changes;
}

/** The drafts with one setting's draft set, or removed when `draft` is `undefined`. */
export function withDraft(drafts: Drafts, key: ProductSettingKey, draft: string | null | undefined): Drafts {
  return Object.fromEntries([
    ...Object.entries(drafts).filter(([other]) => other !== key),
    ...(draft === undefined ? [] : [[key, draft]]),
  ]) as Drafts;
}
