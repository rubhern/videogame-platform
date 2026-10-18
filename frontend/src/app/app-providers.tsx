import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import type { PropsWithChildren } from "react";

export function AppProviders({ children, queryClient }: PropsWithChildren<{ queryClient: QueryClient }>) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
