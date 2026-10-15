import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { completeLogout, getSession, logout, type SessionState } from "./session-api";

export const SESSION_QUERY_KEY = ["session"] as const;

/** Owns the current BFF session state as server state. */
export function useSession() {
  return useQuery<SessionState>({
    queryKey: SESSION_QUERY_KEY,
    queryFn: ({ signal }) => getSession(undefined, signal),
    retry: false,
    staleTime: 0,
  });
}

/** Terminates the application session, then the browser's identity-provider session. */
export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (csrfToken: string) => logout(csrfToken),
    onSuccess: () => {
      // Personal server state must not outlive the session that read it.
      queryClient.removeQueries({ queryKey: ["me"] });
      queryClient.setQueryData<SessionState>(SESSION_QUERY_KEY, { authenticated: false });
      completeLogout();
    },
  });
}
