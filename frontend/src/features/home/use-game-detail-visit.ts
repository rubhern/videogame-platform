import { useEffect, useRef } from "react";

import { rankingConsentStore, useConsent } from "../analytics/consent";
import { reportGameDetailVisit } from "./home-activity-api";

function createVisitId(crypto: Crypto): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();

  // randomUUID requires HTTPS or loopback. getRandomValues also works in private HTTP environments.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

export function useGameDetailVisit(gameId: string | undefined) {
  const consent = useConsent(rankingConsentStore).state;
  const reported = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (gameId === undefined || consent !== "granted") return;
    const report = () => {
      if (
        reported.current === gameId ||
        document.visibilityState !== "visible" ||
        rankingConsentStore.state() !== "granted" ||
        !globalThis.crypto?.getRandomValues
      ) return;

      const visitId = createVisitId(globalThis.crypto);
      reported.current = gameId;
      // Counting is best effort and must never delay the game page or persist an identifier.
      void reportGameDetailVisit(gameId, visitId).catch(() => undefined);
    };
    report();
    document.addEventListener("visibilitychange", report);
    return () => document.removeEventListener("visibilitychange", report);
  }, [gameId, consent]);
}
