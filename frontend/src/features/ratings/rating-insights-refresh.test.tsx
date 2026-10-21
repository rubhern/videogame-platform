import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { PropsWithChildren } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RATING_INSIGHTS_KEY } from "./rating-insights-api";
import { useRatingCommand } from "./use-personal-rating";

const current={gameId:"game-1",mode:"direct" as const,value:7,contribution:7,platforms:[],createdAt:"2026-10-01T10:00:00Z",updatedAt:"2026-10-01T10:00:00Z",entityTag:'"rating-1"'};
const statistics={status:"available",mean:9,count:1,distribution:{"1":0,"2":0,"3":0,"4":0,"5":0,"6":0,"7":0,"8":0,"9":1,"10":0},platforms:[]};
afterEach(() => vi.unstubAllGlobals());

describe("personal insights after rating commands",() => {
  it.each(["save","delete","conflict","ambiguous"] as const)("refreshes an observed private insight after %s",async scenario => {
    vi.stubGlobal("fetch",vi.fn<typeof fetch>().mockImplementation(async () => {
      if(scenario==="ambiguous") throw new TypeError("connection lost");
      if(scenario==="conflict") return Response.json({code:"RATING_WRITE_CONFLICT"},{status:412});
      return Response.json(scenario==="delete" ? {personalRating:null,ratingStatistics:statistics} : {personalRating:{...current,value:9},ratingStatistics:statistics});
    }));
    const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
    let reads=0;
    const wrapper=({children}:PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const hook=renderHook(() => ({
      command:useRatingCommand("game-1"),
      insights:useQuery({queryKey:[...RATING_INSIGHTS_KEY,"document-session"],queryFn:async () => ++reads}),
    }),{wrapper});
    await waitFor(() => expect(hook.result.current.insights.data).toBe(1));
    await act(async () => {
      await hook.result.current.command.mutateAsync(scenario==="delete"
        ? {type:"delete",csrfToken:"csrf",currentRating:current}
        : {type:"save",csrfToken:"csrf",write:{value:9},currentRating:current}).catch(() => undefined);
    });
    await waitFor(() => expect(hook.result.current.insights.data).toBe(2));
  });
});
