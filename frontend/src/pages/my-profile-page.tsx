import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";

import { deleteAccount, exportData, getProfile, ProfileError, saveExport } from "../features/profile/profile-api";
import { authenticationStartUrl } from "../features/session/auth-entry";
import { SESSION_QUERY_KEY, useSession } from "../features/session/use-session";
import { HeroTitle } from "../shared/ui/hero-title";

export function MyProfilePage() {
  const session = useSession();
  const client = useQueryClient();
  const navigate = useNavigate();
  const authenticated = !session.isError && session.data?.authenticated === true;
  const profile = useQuery({ queryKey: ["me", "profile"], queryFn: ({ signal }) => getProfile(signal), enabled: authenticated, retry: false, staleTime: 0 });
  const download = useMutation({ mutationFn: () => exportData(), onSuccess: saveExport });
  const remove = useMutation({ mutationFn: (csrfToken: string) => deleteAccount(csrfToken), onSuccess: () => {
    client.clear();
    client.setQueryData(SESSION_QUERY_KEY, { authenticated: false });
    void navigate("/", { replace: true });
  } });
  const [confirming, setConfirming] = useState(false);
  const [confirmation, setConfirmation] = useState("");
  const cancelRef = useRef<HTMLButtonElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const feedbackRef = useRef<HTMLDivElement>(null);
  const wasConfirming = useRef(false);
  useEffect(() => {
    if (confirming) cancelRef.current?.focus();
    else if (wasConfirming.current) triggerRef.current?.focus();
    wasConfirming.current = confirming;
  }, [confirming]);
  useEffect(() => { if (remove.isError) feedbackRef.current?.focus(); }, [remove.isError]);
  function cancel() { setConfirming(false); setConfirmation(""); remove.reset(); triggerRef.current?.focus(); }
  const stale = profile.error instanceof ProfileError && profile.error.status === 401;
  const recent = remove.error instanceof ProfileError && remove.error.code === "RECENT_AUTHENTICATION_REQUIRED";
  // Authentication is checked before rendering cached personal values, including after expiry.
  const showProfile = authenticated && !stale && profile.isSuccess;

  return <section className="hero-page profile-page" aria-labelledby="profile-title">
    <div className="page-container profile-content">
      <header className="page-head"><p className="eyebrow">Mi cuenta</p><HeroTitle id="profile-title" title="Mi perfil" />
        <p>Tu espacio privado en Gameómetro.</p></header>
      {session.isPending || (authenticated && profile.isPending) ? <p role="status">Cargando tu perfil…</p>
        : session.isError ? <div className="notice" role="alert"><p>No se pudo comprobar tu sesión.</p><button className="button" onClick={() => void session.refetch()}>Reintentar</button></div>
        : !authenticated || stale ? <div className="notice" role="status"><h2>Necesitas una sesión activa</h2><p>Inicia sesión para consultar tu perfil.</p><a className="button button-primary" href={authenticationStartUrl("/mi-perfil")}>Iniciar sesión</a></div>
        : profile.isError ? <div className="notice" role="alert"><p>No se pudo cargar tu perfil.</p><button className="button" onClick={() => void profile.refetch()}>Reintentar</button></div> : null}
      {showProfile ? <>
        <section className="profile-section" aria-labelledby="profile-account"><h2 id="profile-account">Tu cuenta</h2>
          <dl><div><dt>Email</dt><dd>{profile.data.email ?? "No disponible"}</dd></div></dl>
          <nav className="profile-links" aria-label="Tus datos y seguridad"><Link to="/mis-puntuaciones">Mis puntuaciones</Link><Link to="/mis-plataformas">Mis plataformas</Link><a href="/auth/profile/security">Cambiar contraseña</a></nav>
        </section>
        <section className="profile-section" aria-labelledby="profile-export"><h2 id="profile-export">Tus datos, contigo</h2>
          <p>Descarga un archivo JSON con los datos disponibles de tu cuenta, todas tus puntuaciones, las de cada plataforma y tus plataformas preferidas.</p>
          <button className="button button-primary" disabled={download.isPending || remove.isPending} onClick={() => download.mutate()}>{download.isPending ? "Preparando archivo…" : "Exportar mis datos"}</button>
          {download.isError ? <p role="alert">No se pudo preparar la exportación completa. Inténtalo de nuevo o contacta con hola@gameometro.com.</p> : download.isSuccess ? <p role="status">Archivo preparado para descargar.</p> : null}
        </section>
        <section className="profile-section profile-danger" aria-labelledby="profile-delete"><h2 id="profile-delete">Eliminar mi cuenta</h2>
          <p>Se eliminarán tu identidad, todas tus puntuaciones y tus plataformas preferidas. Tus votos dejarán de contribuir a las medias públicas. Puedes exportar tus datos antes.</p>
          <p>Si hay copias de seguridad, el borrado no las elimina al instante y deben respetarse las reglas de conservación y restauración. Solo se conservarán los datos que sean necesarios por obligaciones legales.</p>
          {!profile.data.deletionAvailable ? <p id="profile-delete-unavailable" role="status">El borrado automático no está disponible ahora. Inténtalo más tarde. Si necesitas ayuda, contacta con <a href="mailto:hola@gameometro.com">hola@gameometro.com</a>.</p> : null}
          {!confirming ? <button className="button profile-delete-button" ref={triggerRef} disabled={!profile.data.deletionAvailable} aria-describedby={!profile.data.deletionAvailable ? "profile-delete-unavailable" : undefined} onClick={() => setConfirming(true)}>Eliminar mi cuenta</button>
            : <div role="group" aria-labelledby="profile-confirm" className="profile-confirm" onKeyDown={event => { if (event.key === "Escape" && !remove.isPending) cancel(); }}>
              <h3 id="profile-confirm">¿Confirmas que quieres eliminar tu cuenta?</h3><p>La solicitud bloqueará el acceso mientras se completa. No podrás deshacerla. Solo se confirmará el borrado tras verificarlo; si falla, se reintentará de forma segura.</p>
              <p>Por seguridad, necesitas haberte autenticado en los últimos cinco minutos. <a href="/auth/profile/reauthenticate">Volver a autenticarme</a></p>
              <label htmlFor="profile-confirmation">Escribe ELIMINAR para confirmar</label><input id="profile-confirmation" autoComplete="off" value={confirmation} onChange={event => setConfirmation(event.target.value)} disabled={remove.isPending} />
              <div className="profile-actions"><button ref={cancelRef} className="button" disabled={remove.isPending} onClick={cancel}>Conservar mi cuenta</button><button className="button profile-delete-button" disabled={confirmation !== "ELIMINAR" || remove.isPending} onClick={() => { if (session.data?.authenticated) remove.mutate(session.data.csrfToken); }}>{remove.isPending ? "Enviando solicitud…" : "Sí, solicitar el borrado"}</button></div>
              {remove.isError ? <div role="alert" tabIndex={-1} ref={feedbackRef}>{recent ? <p>Vuelve a autenticarte y confirma de nuevo. La cuenta no se ha borrado.</p> : <p>No se pudo confirmar el resultado. Por seguridad, no repitas la solicitud sin comprobar tu sesión. Si necesitas ayuda, contacta con hola@gameometro.com.</p>}</div> : null}
            </div>}
        </section>
      </> : null}
    </div>
  </section>;
}

/** Retain old bookmarks without making the browser wait for background execution. */
export function AccountDeletionPage() {
  return <Navigate to="/" replace />;
}
