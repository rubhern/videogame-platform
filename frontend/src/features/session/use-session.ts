import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { getSession, logout, type SessionState } from "./session-api";

const SESSION_QUERY_KEY = ["session"] as const;

/** Owns the current BFF session state as server state. */
export function useSession() {
  return useQuery<SessionState>({
    queryKey: SESSION_QUERY_KEY,
    queryFn: ({ signal }) => getSession(undefined, signal),
    retry: false,
    staleTime: 0,
  });
}

/** Terminates the session and refreshes the header to its anonymous state. */
export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (csrfToken: string) => logout(csrfToken),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY }),
  });
}
