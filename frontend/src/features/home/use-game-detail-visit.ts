import { useEffect, useRef } from "react";

import { rankingConsentStore, useConsent } from "../analytics/consent";
import { reportGameDetailVisit } from "./home-activity-api";

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
        !globalThis.crypto?.randomUUID
      ) return;

      reported.current = gameId;
      const visitId = globalThis.crypto.randomUUID();
      // Counting is best effort and must never delay the game page or persist an identifier.
      void reportGameDetailVisit(gameId, visitId).catch(() => undefined);
    };
    report();
    document.addEventListener("visibilitychange", report);
    return () => document.removeEventListener("visibilitychange", report);
  }, [gameId, consent]);
}
