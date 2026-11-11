import { useSyncExternalStore } from "react";

/**
 * The visitor's analytics choice (#507). It is the only thing stored, in this browser alone; the
 * server never learns it. Nothing is stored until the visitor chooses, a choice is asked again
 * after a year, and an unreadable or malformed entry reads as "not chosen yet".
 */
export type ConsentChoice = "granted" | "denied";
export type ConsentState = ConsentChoice | "unset";

export const CONSENT_STORAGE_KEY = "gameometro_analytics_consent";
const CONSENT_VERSION = 1;
const CONSENT_LIFETIME_MS = 365 * 24 * 60 * 60 * 1000;

type StoredConsent = { version: number; choice: ConsentChoice; decidedAt: string };

function read(now: number): ConsentState {
  try {
    const raw = window.localStorage.getItem(CONSENT_STORAGE_KEY);
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

function write(choice: ConsentChoice, now: number) {
  const stored: StoredConsent = {
    version: CONSENT_VERSION,
    choice,
    decidedAt: new Date(now).toISOString(),
  };
  try {
    window.localStorage.setItem(CONSENT_STORAGE_KEY, JSON.stringify(stored));
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

export function createConsentStore(clock: () => number = Date.now): ConsentStore {
  let state: ConsentState = read(clock());
  let reviewing = false;
  const listeners = new Set<Listener>();
  const notify = () => listeners.forEach((listener) => listener());
  return {
    state: () => state,
    reviewing: () => reviewing,
    choose: (choice) => {
      write(choice, clock());
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
      state = read(clock());
      reviewing = false;
      notify();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

export const consentStore = createConsentStore();

export function useConsent(store: ConsentStore = consentStore) {
  const state = useSyncExternalStore(store.subscribe, store.state);
  const reviewing = useSyncExternalStore(store.subscribe, store.reviewing);
  return { state, reviewing, choose: store.choose, review: store.review };
}
