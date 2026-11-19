import { useSyncExternalStore } from "react";

/**
 * The visitor's analytics choice (#507). It is the only thing stored, in this browser alone; the
 * server never learns it. Nothing is stored until the visitor chooses, a choice is asked again
 * after a year, and an unreadable or malformed entry reads as "not chosen yet".
 */
export type ConsentChoice = "granted" | "denied";
export type ConsentState = ConsentChoice | "unset";

export const CONSENT_STORAGE_KEY = "gameometro_analytics_consent";
export const RANKING_CONSENT_STORAGE_KEY = "gameometro_visit_ranking_consent";
const CONSENT_VERSION = 1;
const CONSENT_LIFETIME_MS = 365 * 24 * 60 * 60 * 1000;

type StoredConsent = { version: number; choice: ConsentChoice; decidedAt: string };

function read(storageKey: string, now: number): ConsentState {
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (raw === null) return "unset";
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) return "unset";
    const stored = value as Partial<StoredConsent>;
    const decidedAt = typeof stored.decidedAt === "string" ? Date.parse(stored.decidedAt) : NaN;
    if (
      stored.version !== CONSENT_VERSION ||
      (stored.choice !== "granted" && stored.choice !== "denied") ||
      Number.isNaN(decidedAt) ||
      now - decidedAt > CONSENT_LIFETIME_MS
    ) {
      return "unset";
    }
    return stored.choice;
  } catch {
    return "unset";
  }
}

function write(storageKey: string, choice: ConsentChoice, now: number) {
  const stored: StoredConsent = {
    version: CONSENT_VERSION,
    choice,
    decidedAt: new Date(now).toISOString(),
  };
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(stored));
  } catch {
    // Without storage the choice still holds for this document; it is asked again next visit.
  }
}

type Listener = () => void;

/** One document-wide store: the banner, the footer settings and the tracker share it. */
export type ConsentStore = {
  state: () => ConsentState;
  /** Whether the visitor asked to review their choice from the footer. */
  reviewing: () => boolean;
  choose: (choice: ConsentChoice) => void;
  review: () => void;
  /** Re-reads the stored choice, e.g. after storage changed outside this document. */
  reload: () => void;
  subscribe: (listener: Listener) => () => void;
};

export function createConsentStore(
  clock: () => number = Date.now,
  storageKey = CONSENT_STORAGE_KEY,
): ConsentStore {
  let state: ConsentState = read(storageKey, clock());
  let reviewing = false;
  const listeners = new Set<Listener>();
  const notify = () => listeners.forEach((listener) => listener());
  function onStorage(event: StorageEvent) {
    if (event.key !== storageKey && event.key !== null) return;
    state = read(storageKey, clock());
    notify();
  }

  return {
    state: () => state,
    reviewing: () => reviewing,
    choose: (choice) => {
      write(storageKey, choice, clock());
      state = choice;
      reviewing = false;
      notify();
    },
    review: () => {
      if (reviewing) return;
      reviewing = true;
      notify();
    },
    reload: () => {
      state = read(storageKey, clock());
      reviewing = false;
      notify();
    },
    subscribe: (listener) => {
      if (listeners.size === 0) window.addEventListener("storage", onStorage);
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
        if (listeners.size === 0) window.removeEventListener("storage", onStorage);
      };
    },
  };
}

export const consentStore = createConsentStore();
export const rankingConsentStore = createConsentStore(Date.now, RANKING_CONSENT_STORAGE_KEY);

export function useConsent(store: ConsentStore = consentStore) {
  const state = useSyncExternalStore(store.subscribe, store.state);
  const reviewing = useSyncExternalStore(store.subscribe, store.reviewing);
  return { state, reviewing, choose: store.choose, review: store.review };
}
