import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { PlatformPicker } from "../features/platform-preferences/platform-picker";
import { selectionSummary } from "../features/platform-preferences/selection-summary";
import { selectablePlatforms } from "../features/platform-preferences/selectable-platforms";
import {
  useMyPlatformPreferences,
  usePlatformList,
  useSavePlatformPreferences,
} from "../features/platform-preferences/use-platform-preferences";
import { toggleFilterValue } from "../features/releases/releases-search";
import { authenticationStartUrl } from "../features/session/auth-entry";
import { useSession } from "../features/session/use-session";
import { HeroTitle } from "../shared/ui/hero-title";

function sameSet(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value) => right.includes(value));
}

/**
 * **Mis plataformas** (#295): where a signed-in visitor manages the preferred platforms that
 * **Según mis intereses** applies to TOPómetro and Explorar lanzamientos. Changes are a draft until
 * saved, so browsing the tiles never sends a command; saving replaces the whole set, and an empty
 * set clears it. Turning interests off on a discovery screen never changes what is saved here.
 */
export function MyPlatformsPage() {
  const session = useSession();
  const csrfToken = session.data?.authenticated === true ? session.data.csrfToken : null;
  const preferences = useMyPlatformPreferences();
  const platforms = usePlatformList();
  const save = useSavePlatformPreferences();
  const [draft, setDraft] = useState<string[] | null>(null);
  const [message, setMessage] = useState("");
  const alertRef = useRef<HTMLDivElement>(null);
  const saved = (preferences.data?.platforms ?? []).map((platform) => platform.platformId);
  const selected = draft ?? saved;
  const changed = draft !== null && !sameSet(draft, saved);

  useEffect(() => {
    if (save.isError) alertRef.current?.focus();
  }, [save.isError]);

  function edit(next: string[]) {
    setMessage("");
    save.reset();
    setDraft(next);
  }

  function commit() {
    if (csrfToken === null) return;
    save.mutate(
      { platformIds: selected, csrfToken },
      {
        onSuccess: () => {
          setDraft(null);
          setMessage("Selección guardada");
        },
      },
    );
  }

  return (
    <section aria-labelledby="my-platforms-title" className="hero-page preferences-page my-platforms-page">
      <div className="page-container preferences-section">
        <header className="page-head preferences-head">
          <p className="eyebrow">Mi cuenta</p>
          <HeroTitle id="my-platforms-title" title="Mis plataformas" />
          <p className="preferences-lead">
            Elige las plataformas que saldrán por defecto. Puedes modificarlo desde el menú de Mi
            cuenta
          </p>
        </header>

        {session.isPending || (csrfToken !== null && preferences.isPending) ? (
          <p className="result-count" role="status">Cargando tus plataformas…</p>
        ) : csrfToken === null || preferences.error?.kind === "authentication" ? (
          <div className="notice notice-empty" role="status">
            <h2>Necesitas una sesión activa</h2>
            <p>Inicia sesión para elegir tus plataformas.</p>
            <a className="button button-primary" href={authenticationStartUrl("/mis-plataformas")}>
              Iniciar sesión
            </a>
          </div>
        ) : preferences.isError || platforms.isError ? (
          <div className="notice notice-danger" role="alert">
            <p className="notice-kicker">Error de carga</p>
            <p>No se pudieron cargar tus plataformas. Inténtalo de nuevo.</p>
            <button
              className="button button-danger"
              onClick={() => {
                void preferences.refetch();
                void platforms.refetch();
              }}
              type="button"
            >
              Reintentar carga
            </button>
          </div>
        ) : platforms.isPending ? (
          <p className="result-count" role="status">Cargando plataformas…</p>
        ) : selectablePlatforms(platforms.data).length === 0 ? (
          <div className="notice notice-empty" role="status">
            <p className="notice-kicker">Sin plataformas</p>
            <p>Todavía no hay plataformas en el catálogo.</p>
          </div>
        ) : (
          <>
            <PlatformPicker
              disabled={save.isPending}
              legend="Mis plataformas"
              onToggle={(platformId) => edit(toggleFilterValue(selected, platformId))}
              platforms={platforms.data}
              selected={selected}
            />
            {save.isError ? (
              <div className="notice notice-danger" ref={alertRef} role="alert" tabIndex={-1}>
                <p className="notice-kicker">No se guardó</p>
                <p>
                  {save.error.kind === "authentication"
                    ? "Tu sesión ha terminado. Vuelve a iniciar sesión para guardar tus plataformas."
                    : "No se pudieron guardar tus plataformas. Tu elección anterior se mantiene."}
                </p>
              </div>
            ) : null}
            <div className="preferences-actions">
              <p className="preferences-count" role="status">
                {save.isPending
                  ? "Guardando…"
                  : message || `${selectionSummary(selected.length)}${changed ? " · Sin guardar" : ""}`}
              </p>
              <button
                className="button button-primary"
                disabled={save.isPending || !changed}
                onClick={commit}
                type="button"
              >
                Guardar cambios
              </button>
              <button
                className="button preferences-skip"
                disabled={save.isPending || selected.length === 0}
                onClick={() => edit([])}
                type="button"
              >
                Quitar todas
              </button>
            </div>
            <p className="preferences-links">
              <Link className="view-action" to="/">
                Volver a Gameómetro <span aria-hidden="true">→</span>
              </Link>
            </p>
          </>
        )}
      </div>
    </section>
  );
}
