import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import type { PropsWithChildren } from "react";

import {
  InterestsSwitchContext,
  type InterestsSwitch,
} from "../features/platform-preferences/interests-switch";

export function AppProviders({
  children,
  queryClient,
  interests,
}: PropsWithChildren<{ queryClient: QueryClient; interests: InterestsSwitch }>) {
  return (
    <QueryClientProvider client={queryClient}>
      <InterestsSwitchContext.Provider value={interests}>{children}</InterestsSwitchContext.Provider>
    </QueryClientProvider>
  );
}
