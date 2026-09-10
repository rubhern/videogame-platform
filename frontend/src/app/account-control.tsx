import { useLogout, useSession } from "../features/session/use-session";

/**
 * Header account control.
 *
 * <p>It appears only for an authenticated session and provides the MVP logout action. Anonymous
 * browsing shows no account or general login entry point: authentication begins at the rating
 * boundary, not here.
 */
export function AccountControl() {
  const session = useSession();
  const logout = useLogout();

  if (session.data?.authenticated !== true) {
    return null;
  }

  const csrfToken = session.data.csrfToken;

  return (
    <div className="account-control">
      <span className="account-avatar" aria-hidden="true">
        <span />
      </span>
      <span className="account-label">Mi cuenta</span>
      <button
        type="button"
        className="button account-logout"
        disabled={logout.isPending}
        onClick={() => logout.mutate(csrfToken)}
      >
        {logout.isPending ? "Cerrando…" : "Cerrar sesión"}
      </button>
    </div>
  );
}
