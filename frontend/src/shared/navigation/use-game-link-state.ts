import { useLocation } from "react-router-dom";

import { gameLinkState, type GameLinkState } from "./return-context";

/** The origin a link from the current screen into a game page carries (#285). */
export function useGameLinkState(): GameLinkState | undefined {
  return gameLinkState(useLocation());
}
