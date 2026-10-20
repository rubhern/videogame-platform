import { createContext, useContext, useSyncExternalStore } from "react";

export type InterestsSwitch = {
  enabled: () => boolean;
  set: (enabled: boolean) => void;
  subscribe: (listener: () => void) => () => void;
};

/** One switch per document, shared by route loaders and React without browser storage. */
export function createInterestsSwitch(): InterestsSwitch {
  let enabled = true;
  const listeners = new Set<() => void>();
  return {
    enabled: () => enabled,
    set: (next) => {
      if (next === enabled) return;
      enabled = next;
      listeners.forEach((listener) => listener());
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
  };
}

export const InterestsSwitchContext = createContext<InterestsSwitch | null>(null);

export function useInterestsSwitch() {
  const store = useContext(InterestsSwitchContext);
  if (store === null) throw new Error("InterestsSwitchContext is missing.");
  const enabled = useSyncExternalStore(store.subscribe, store.enabled);
  return { enabled, set: store.set };
}
