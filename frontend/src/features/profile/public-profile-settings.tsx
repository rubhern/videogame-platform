import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { ProfileError } from "./profile-api";
import { ProfileShareImage } from "./profile-share-image";
import {
  ALIAS_PATTERN,
  getMyPublicProfileSettings,
  isPlayerAlias,
  PROFILE_SETTINGS_KEY,
  PUBLIC_PROFILES_KEY,
  publicProfilePath,
  updateMyPublicProfileSettings,
  type PublicProfileChange,
} from "./public-profile-api";

function settingsError(error: Error) {
  if (error instanceof ProfileError) {
    switch (error.code) {
      case "PUBLIC_ALIAS_UNAVAILABLE": return "Ese alias no está disponible. Elige otro.";
      case "PUBLIC_ALIAS_INVALID": return "El alias no es válido o está reservado.";
      case "PUBLIC_ALIAS_CHANGE_TOO_SOON": return "Puedes cambiar el alias pasados 30 días desde que lo elegiste.";
      case "PUBLIC_PROFILE_SECTION_REQUIRED": return "Marca al menos una sección para publicar.";
      case "PUBLIC_PROFILE_REVISION_CONFLICT": return "Los ajustes cambiaron desde que los abriste. Carga la versión guardada.";
    }
    if (error.status === 401) return "Tu sesión ha caducado. Vuelve a iniciar sesión.";
  }
  return "No se pudo confirmar el guardado. Comprueba los ajustes e inténtalo de nuevo.";
}

export function PublicProfileSettingsPanel({ csrfToken }: { csrfToken: string }) {
  const client = useQueryClient();
  const [draft, setDraft] = useState<PublicProfileChange | null>(null);
  const settings = useQuery({
    queryKey: PROFILE_SETTINGS_KEY,
    queryFn: ({ signal }) => getMyPublicProfileSettings(signal),
    retry: false,
  });
  const save = useMutation({
    mutationFn: (command: PublicProfileChange) => updateMyPublicProfileSettings(command, csrfToken),
    onSuccess: result => {
      setDraft(null);
      client.setQueryData(PROFILE_SETTINGS_KEY, result);
      void client.invalidateQueries({ queryKey: PUBLIC_PROFILES_KEY });
    },
  });
  const saved = settings.data;
  const initial = saved ? { ...saved, alias: saved.alias ?? "" } : null;
  const current = draft ?? initial;
  const canPublish = current && current.published && (current.shareRatings || current.shareWishlist)
    && isPlayerAlias(current.alias);
  const aliasError = save.error instanceof ProfileError
    && (save.error.code === "PUBLIC_ALIAS_UNAVAILABLE" || save.error.code === "PUBLIC_ALIAS_INVALID"
      || save.error.code === "PUBLIC_ALIAS_CHANGE_TOO_SOON")
    ? settingsError(save.error)
    : null;

  function edit(change: Partial<PublicProfileChange>) {
    if (!current) return;
    save.reset();
    setDraft({ ...current, ...change });
  }

  function restoreSaved() {
    setDraft(null);
    save.reset();
    void settings.refetch();
  }

  return (
    <section aria-labelledby="profile-sharing" className="profile-panel public-profile-settings">
      <h2 id="profile-sharing">Perfil público</h2>
      <p>
        Elige qué quieres compartir con cualquiera que tenga el enlace.
        {" "}<Link className="text-link" to="/privacidad" target="_blank" rel="noopener">
          Cómo usamos estos datos<span className="sr-only"> (se abre en una pestaña nueva)</span>
        </Link>
      </p>
      {settings.isPending ? <p role="status">Cargando ajustes de publicación…</p> : null}
      {settings.isError ? (
        <>
          <p role="alert">No se pudieron cargar los ajustes de publicación.</p>
          <button type="button" className="button" onClick={() => void settings.refetch()}>Reintentar</button>
        </>
      ) : current && initial ? (
        <>
          <form className="public-profile-form" onSubmit={event => {
            event.preventDefault();
            if (draft && canPublish && !save.isPending) save.mutate(draft);
          }}>
            <label htmlFor="public-player-alias">Alias</label>
            <input id="public-player-alias" autoComplete="off" spellCheck={false}
              value={current.alias} minLength={3} maxLength={24} pattern={ALIAS_PATTERN}
              aria-describedby={aliasError ? "public-player-alias-error public-player-alias-help" : "public-player-alias-help"}
              aria-invalid={aliasError ? true : undefined} required disabled={save.isPending}
              onChange={event => edit({ alias: event.target.value })} />
            {aliasError ? (
              <p id="public-player-alias-error" className="profile-feedback profile-feedback-error" role="alert">{aliasError}</p>
            ) : null}
            <p id="public-player-alias-help">
              De 3 a 24 letras sin tilde, números o guiones entre palabras. Puedes cambiarlo cada 30 días.
            </p>
            <fieldset disabled={save.isPending}>
              <legend>Visibilidad</legend>
              <label><input type="checkbox" checked={current.published}
                onChange={event => edit(event.target.checked
                  ? { published: true }
                  : { published: false, shareRatings: false, shareWishlist: false })} /> Publicar mi perfil</label>
              <label><input type="checkbox" checked={current.shareRatings} disabled={!current.published}
                onChange={event => edit({ shareRatings: event.target.checked })} /> Mostrar mis puntuaciones</label>
              <label><input type="checkbox" checked={current.shareWishlist} disabled={!current.published}
                onChange={event => edit({ shareWishlist: event.target.checked })} /> Mostrar mis deseados</label>
            </fieldset>
            {current.published && !current.shareRatings && !current.shareWishlist
              ? <p>Marca al menos una sección para publicar.</p>
              : null}
            <div className="public-profile-actions">
              <button type="submit" className="button button-primary" disabled={draft === null || !canPublish || save.isPending}>
                {save.isPending ? "Guardando…" : "Guardar"}
              </button>
              {initial.published ? (
                <button type="button" className="button" disabled={save.isPending}
                  onClick={() => save.mutate({
                    ...initial, published: false, shareRatings: false, shareWishlist: false,
                  })}>Hacer privado</button>
              ) : null}
            </div>
          </form>
          {save.error && !aliasError ? (
            <div role="alert">
              <p>{settingsError(save.error)}</p>
              {save.error instanceof ProfileError && save.error.status === 409
                  && save.error.code === "PUBLIC_PROFILE_REVISION_CONFLICT" ? (
                <button type="button" className="button" onClick={restoreSaved}>Cargar ajustes guardados</button>
              ) : null}
            </div>
          ) : null}
          {save.isSuccess ? (
            <p role="status">{initial.published ? "Ajustes de publicación guardados." : "Tu perfil es privado."}</p>
          ) : null}
          {initial.published ? (
            <div className="public-profile-actions">
              <Link className="text-link" to={publicProfilePath(initial.alias)}>Ver @{initial.alias}</Link>
              <ShareProfileButton key={initial.alias} alias={initial.alias} />
            </div>
          ) : null}
          {initial.published ? <ProfileShareImage key={initial.alias} alias={initial.alias} /> : null}
        </>
      ) : null}
    </section>
  );
}

function ShareProfileButton({ alias }: { alias: string }) {
  const [status, setStatus] = useState<"copied" | "manual" | null>(null);
  const path = publicProfilePath(alias);
  const message = "¡Mira mi perfil en Gameómetro!";

  async function share() {
    const url = new URL(path, window.location.origin).href;
    setStatus(null);
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title: `@${alias} · Gameómetro`, text: message, url });
        return;
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") return;
      }
    }
    try {
      await navigator.clipboard.writeText(`${message}\n${url}`);
      setStatus("copied");
    } catch {
      setStatus("manual");
    }
  }

  return (
    <div className="public-profile-share">
      <button type="button" className="public-profile-share-button" aria-label="Compartir perfil"
        title="Compartir perfil" onClick={() => void share()}>
        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65">
          <circle cx="17.5" cy="5" r="2.65" />
          <circle cx="6.5" cy="12" r="2.65" />
          <circle cx="17.5" cy="19" r="2.65" />
          <path d="m8.75 10.55 6.5-4.1m-6.5 7 6.5 4.1" />
        </svg>
      </button>
      {status === "copied" ? <span role="status">Mensaje y enlace copiados.</span> : null}
      {status === "manual" ? (
        <p role="status">{message}<br />
          <a className="text-link" href={path}>{new URL(path, window.location.origin).href}</a>
        </p>
      ) : null}
    </div>
  );
}
