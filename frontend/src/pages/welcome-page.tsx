import { useEffect, useRef, useState, type ReactNode } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";

import { PlatformPicker } from "../features/platform-preferences/platform-picker";
import { selectionSummary } from "../features/platform-preferences/selection-summary";
import { selectablePlatforms } from "../features/platform-preferences/selectable-platforms";
import {
  useMyPlatformPreferences,
  usePlatformList,
  useSavePlatformPreferences,
} from "../features/platform-preferences/use-platform-preferences";
import { safeWelcomeReturn } from "../features/platform-preferences/welcome-return";
import { toggleFilterValue } from "../features/releases/releases-search";
import { useSession } from "../features/session/use-session";
import { HeroTitle } from "../shared/ui/hero-title";

function WelcomeLoading({ message }: { message: string }) {
  return (
    <>
      <p className="sr-only" role="status">{message}</p>
      <ul aria-hidden="true" className="platform-tiles platform-tiles-loading">
        {Array.from({ length: 6 }, (_, index) => (
          <li key={index}><span className="skeleton platform-tile-skeleton" /></li>
        ))}
      </ul>
    </>
  );
}

/**
 * The first-run choice of preferred platforms (#295), offered once to a signed-in visitor who has
 * never chosen, after Keycloak hands the session back and before discovery. It is product UI, not a
 * registration step: saving or skipping records the choice so it is never offered again, and both
 * continue to the discovery destination the visitor was heading to.
 */
export function WelcomePage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const returnTo = safeWelcomeReturn(params.get("returnTo"));
  const session = useSession();
  const preferences = useMyPlatformPreferences();
  const platforms = usePlatformList();
  const save = useSavePlatformPreferences();
  const [selected, setSelected] = useState<string[]>([]);
  const [skipping, setSkipping] = useState(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const csrfToken = session.data?.authenticated === true ? session.data.csrfToken : null;

  useEffect(() => {
    if (save.isError && !skipping) alertRef.current?.focus();
  }, [save.isError, skipping]);

  if (session.isPending || (csrfToken !== null && preferences.isPending)) {
    return <WelcomeShell><WelcomeLoading message="Preparando tu bienvenida…" /></WelcomeShell>;
  }
  // Only a signed-in visitor who has not chosen yet is welcomed; anyone else simply continues.
  if (csrfToken === null || (preferences.data?.configured === true && save.isIdle)) {
    return <Navigate replace to={returnTo} />;
  }

  function finish(platformIds: readonly string[], skip: boolean) {
    if (csrfToken === null) return;
    setSkipping(skip);
    save.mutate(
      { platformIds, csrfToken },
      {
        onSuccess: () => void navigate(returnTo, { replace: true }),
        // Skipping never blocks discovery; the offer simply stays for a later visit.
        onError: () => {
          if (skip) void navigate(returnTo, { replace: true });
        },
      },
    );
  }

  const list = selectablePlatforms(platforms.data ?? []);
  return (
    <WelcomeShell>
      {platforms.isPending ? <WelcomeLoading message="Cargando plataformas…" /> : null}
      {platforms.isError ? (
        <div className="notice notice-danger" role="alert">
          <p className="notice-kicker">Error de carga</p>
          <p>No se pudieron cargar las plataformas. Puedes elegirlas más tarde en Mi cuenta.</p>
          <button className="button button-danger" onClick={() => void platforms.refetch()} type="button">
            Reintentar
          </button>
        </div>
      ) : null}
      {platforms.isSuccess && list.length === 0 ? (
        <div className="notice notice-empty" role="status">
          <p className="notice-kicker">Sin plataformas</p>
          <p>Todavía no hay plataformas en el catálogo. Podrás elegirlas más adelante en Mi cuenta.</p>
        </div>
      ) : null}
      {platforms.isSuccess && list.length > 0 ? (
        <PlatformPicker
          disabled={save.isPending}
          legend="Tus plataformas"
          onToggle={(platformId) => setSelected((current) => toggleFilterValue(current, platformId))}
          platforms={list}
          selected={selected}
        />
      ) : null}
      {save.isError && !skipping ? (
        <div className="notice notice-danger welcome-alert" ref={alertRef} role="alert" tabIndex={-1}>
          <p className="notice-kicker">No se guardó</p>
          <p>
            {save.error.kind === "authentication"
              ? "Tu sesión ha terminado. Vuelve a iniciar sesión para guardar tus plataformas."
              : "No se pudieron guardar tus plataformas. Vuelve a intentarlo."}
          </p>
        </div>
      ) : null}
      <div className="preferences-actions">
        <p className="preferences-count" role="status">
          {save.isPending ? "Guardando…" : selectionSummary(selected.length)}
        </p>
        <button
          className="button button-primary"
          disabled={save.isPending || selected.length === 0}
          onClick={() => finish(selected, false)}
          type="button"
        >
          Guardar y continuar
        </button>
        <button
          className="button preferences-skip"
          disabled={save.isPending}
          onClick={() => finish([], true)}
          type="button"
        >
          Ahora no
        </button>
      </div>
      <p className="preferences-footnote">
        Puedes cambiarlas cuando quieras desde <strong>Mi cuenta › Mis plataformas</strong>.
      </p>
    </WelcomeShell>
  );
}

function WelcomeShell({ children }: { children: ReactNode }) {
  return (
    <section aria-labelledby="welcome-title" className="hero-page preferences-page welcome-page">
      <div className="page-container preferences-section">
        <header className="page-head preferences-head">
          <p className="eyebrow">Te damos la bienvenida a Gameómetro</p>
          <HeroTitle id="welcome-title" title="¿A qué juegas?" />
          <p className="preferences-lead">
            Elige tus plataformas y te mostraremos primero los juegos que responden a tus intereses.
            Elige varias, o ninguna si prefieres verlo todo.
          </p>
        </header>
        {children}
      </div>
    </section>
  );
}
