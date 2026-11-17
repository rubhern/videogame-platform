import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";

import { deleteAccount, exportData, getProfile, ProfileError, saveExport } from "../features/profile/profile-api";
import { authenticationStartUrl } from "../features/session/auth-entry";
import { SESSION_QUERY_KEY, useSession } from "../features/session/use-session";
import { HeroTitle } from "../shared/ui/hero-title";

/** The channel marker of a zone: its index in the instrument's readout voice, decorative only. */
function ZoneIndex({ value }: { value: string }) {
  return (
    <span aria-hidden="true" className="profile-index">
      {value}
    </span>
  );
}

/** A destination the account already owns. The arrow is decorative, so the link keeps its name. */
function Destination({ children, icon, to }: { children: string; icon: ReactNode; to: string }) {
  const content = (
    <>
      <span aria-hidden="true" className="profile-destination-glyph">
        {icon}
      </span>
      <span className="profile-destination-name">{children}</span>
      <span aria-hidden="true" className="profile-destination-arrow">
        →
      </span>
    </>
  );
  // Keycloak owns account security, so that destination leaves the SPA through the BFF path.
  return to.startsWith("/auth/") ? (
    <a className="profile-destination" href={to}>
      {content}
    </a>
  ) : (
    <Link className="profile-destination" to={to}>
      {content}
    </Link>
  );
}

/**
 * **Mi perfil** (#272, redesigned by #476): the private account read as the visitor's own console
 * on night. The opening lights the stage in ice alone — the screen has no artwork and no value for
 * an instrument — the credential plate carries the identity, and an ice channel runs down the three
 * zones so the irreversible one stays visibly apart in its danger tone.
 */
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

  return (
    <section aria-labelledby="profile-title" className="hero-page profile-page">
      {/* The stage is painted in CSS: ice alone, as the hosted identity screens light theirs. */}
      <div aria-hidden="true" className="profile-stage" />
      <div className="page-container profile-content">
        <header className="page-head profile-head">
          <p className="eyebrow">Mi cuenta</p>
          <HeroTitle id="profile-title" title="Mi perfil" />
          <span aria-hidden="true" className="profile-head-rule" />
          <p className="profile-lead">Tu espacio privado en Gameómetro.</p>
        </header>
        {session.isPending || (authenticated && profile.isPending) ? (
          <div className="profile-waiting">
            <p role="status">Cargando tu perfil…</p>
            <div aria-hidden="true" className="profile-console">
              {["01", "02", "03"].map(zone => (
                <div className="profile-panel profile-panel-placeholder" key={zone}>
                  <ZoneIndex value={zone} />
                  <span className="skeleton skeleton-title" />
                  <span className="skeleton skeleton-meta" />
                </div>
              ))}
            </div>
          </div>
        ) : session.isError ? (
          <div className="notice notice-danger" role="alert">
            <p className="notice-kicker">Sesión</p>
            <p>No se pudo comprobar tu sesión.</p>
            <button className="button" onClick={() => void session.refetch()}>Reintentar</button>
          </div>
        ) : !authenticated || stale ? (
          <div className="notice notice-info" role="status">
            <p className="notice-kicker">Acceso</p>
            <h2>Necesitas una sesión activa</h2>
            <p>Inicia sesión para consultar tu perfil.</p>
            <a className="button button-primary" href={authenticationStartUrl("/mi-perfil")}>Iniciar sesión</a>
          </div>
        ) : profile.isError ? (
          <div className="notice notice-danger" role="alert">
            <p className="notice-kicker">Perfil</p>
            <p>No se pudo cargar tu perfil.</p>
            <button className="button" onClick={() => void profile.refetch()}>Reintentar</button>
          </div>
        ) : null}
        {showProfile ? (
          <div className="profile-console">
            <section aria-labelledby="profile-account" className="profile-panel profile-identity">
              <ZoneIndex value="01" />
              <h2 id="profile-account">Tu cuenta</h2>
              <div className="profile-plate">
                <span aria-hidden="true" className="profile-medallion">
                  <svg fill="none" viewBox="0 0 20 20">
                    <circle cx="10" cy="6" r="3" stroke="currentColor" strokeWidth="1.5" />
                    <path d="M4 17v-2a6 6 0 0 1 12 0v2H4Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
                  </svg>
                </span>
                <dl className="profile-facts">
                  <div>
                    <dt>Email</dt>
                    <dd className={profile.data.email ? undefined : "profile-missing"}>{profile.data.email ?? "No disponible"}</dd>
                  </div>
                </dl>
              </div>
              <nav aria-label="Tus datos y seguridad" className="profile-links">
                <Destination
                  icon={<svg fill="none" viewBox="0 0 20 20"><path d="M10 2.5 12.3 7l5 .7-3.6 3.5.8 5-4.5-2.3-4.5 2.3.8-5L2.7 7l5-.7L10 2.5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" /></svg>}
                  to="/mis-puntuaciones"
                >
                  Mis puntuaciones
                </Destination>
                <Destination
                  icon={<svg fill="none" viewBox="0 0 20 20"><rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="2.75" y="2.75" /><rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="11.25" y="2.75" /><rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="2.75" y="11.25" /><rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="11.25" y="11.25" /></svg>}
                  to="/mis-plataformas"
                >
                  Mis plataformas
                </Destination>
                <Destination
                  icon={<svg fill="none" viewBox="0 0 20 20"><rect height="8.5" rx="1.5" stroke="currentColor" strokeWidth="1.5" width="12.5" x="3.75" y="8.5" /><path d="M6.75 8.5V6a3.25 3.25 0 0 1 6.5 0v2.5" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" /><circle cx="10" cy="12.75" fill="currentColor" r="1.15" /></svg>}
                  to="/auth/profile/security"
                >
                  Cambiar contraseña
                </Destination>
              </nav>
            </section>
            <section aria-labelledby="profile-export" className="profile-panel profile-data">
              <ZoneIndex value="02" />
              <h2 id="profile-export">Tus datos, contigo</h2>
              <p>Descarga un archivo JSON con los datos disponibles de tu cuenta, todas tus puntuaciones, las de cada plataforma y tus plataformas preferidas.</p>
              <div className="profile-panel-actions">
                <button className="button button-primary" disabled={download.isPending || remove.isPending} onClick={() => download.mutate()}>{download.isPending ? "Preparando archivo…" : "Exportar mis datos"}</button>
              </div>
              {download.isError ? <p className="profile-feedback profile-feedback-error" role="alert">No se pudo preparar la exportación completa. Inténtalo de nuevo o contacta con hola@gameometro.com.</p>
                : download.isSuccess ? <p className="profile-feedback profile-feedback-done" role="status">Archivo preparado para descargar.</p> : null}
            </section>
            <section aria-labelledby="profile-delete" className="profile-panel profile-danger">
              <ZoneIndex value="03" />
              <h2 id="profile-delete">Eliminar mi cuenta</h2>
              <p>Se eliminarán tu identidad, todas tus puntuaciones y tus plataformas preferidas. Tus votos dejarán de contribuir a las medias públicas. Puedes exportar tus datos antes.</p>
              {!profile.data.deletionAvailable ? <p className="profile-feedback" id="profile-delete-unavailable" role="status">El borrado automático no está disponible ahora. Inténtalo más tarde. Si necesitas ayuda, contacta con <a className="text-link" href="mailto:hola@gameometro.com">hola@gameometro.com</a>.</p> : null}
              {!confirming ? (
                <div className="profile-panel-actions">
                  <button aria-describedby={!profile.data.deletionAvailable ? "profile-delete-unavailable" : undefined} className="button button-danger" disabled={!profile.data.deletionAvailable} onClick={() => setConfirming(true)} ref={triggerRef}>Eliminar mi cuenta</button>
                </div>
              ) : (
                <div aria-labelledby="profile-confirm" className="profile-confirm" onKeyDown={event => { if (event.key === "Escape" && !remove.isPending) cancel(); }} role="group">
                  <h3 id="profile-confirm">¿Confirmas que quieres eliminar tu cuenta?</h3>
                  <p>La solicitud bloqueará el acceso mientras se completa. No podrás deshacerla. Solo se confirmará el borrado tras verificarlo; si falla, se reintentará de forma segura.</p>
                  <p>Por seguridad, necesitas haberte autenticado en los últimos cinco minutos. <a className="text-link" href="/auth/profile/reauthenticate">Volver a autenticarme</a></p>
                  <label htmlFor="profile-confirmation">Escribe ELIMINAR para confirmar</label>
                  <input autoComplete="off" disabled={remove.isPending} id="profile-confirmation" onChange={event => setConfirmation(event.target.value)} value={confirmation} />
                  <div className="profile-actions">
                    <button className="button" disabled={remove.isPending} onClick={cancel} ref={cancelRef}>Conservar mi cuenta</button>
                    <button className="button button-danger" disabled={confirmation !== "ELIMINAR" || remove.isPending} onClick={() => { if (session.data?.authenticated) remove.mutate(session.data.csrfToken); }}>{remove.isPending ? "Enviando solicitud…" : "Sí, solicitar el borrado"}</button>
                  </div>
                  {remove.isError ? (
                    <div className="profile-feedback profile-feedback-error" ref={feedbackRef} role="alert" tabIndex={-1}>
                      {recent ? <p>Vuelve a autenticarte y confirma de nuevo. La cuenta no se ha borrado.</p>
                        : <p>No se pudo confirmar el resultado. Por seguridad, no repitas la solicitud sin comprobar tu sesión. Si necesitas ayuda, contacta con hola@gameometro.com.</p>}
                    </div>
                  ) : null}
                </div>
              )}
            </section>
          </div>
        ) : null}
      </div>
    </section>
  );
}

/** Retain old bookmarks without making the browser wait for background execution. */
export function AccountDeletionPage() {
  return <Navigate to="/" replace />;
}
