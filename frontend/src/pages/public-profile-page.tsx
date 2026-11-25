import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { ProfileError } from "../features/profile/profile-api";
import { getPublicProfile, PUBLIC_PROFILES_KEY } from "../features/profile/public-profile-api";
import { PublicProfileContent } from "../features/profile/public-profile-content";
import { useDocumentTitle } from "../shared/browser/use-document-title";
import { NotFoundPage } from "./not-found-page";

export function PublicProfilePage() {
  const { alias = "" } = useParams();
  const { data, error, isPending, refetch } = useQuery({
    queryKey: [...PUBLIC_PROFILES_KEY, alias],
    queryFn: ({ signal }) => getPublicProfile(alias, signal),
    gcTime: 0,
    staleTime: 0,
    retry: false,
  });
  const initialSection = data?.sections[0];
  useDocumentTitle(data && !error ? `@${data.alias} · Gameómetro` : "Perfil · Gameómetro");

  if (error instanceof ProfileError && error.status === 404) return <NotFoundPage />;
  if (!error && data) {
    return initialSection ? (
      <PublicProfileContent key={data.alias} alias={data.alias} sections={data.sections} initialSection={initialSection} />
    ) : <NotFoundPage />;
  }

  return (
    <div className="hero-page profile-page">
      <div aria-hidden="true" className="profile-stage" />
      <div className="page-container profile-content">
        {isPending ? <p role="status">Cargando perfil…</p> : (
          <div className="notice notice-danger" role="alert">
            <h1>Perfil</h1>
            <p>No se pudo cargar este perfil.</p>
            <button className="button" onClick={() => void refetch()}>Reintentar</button>
          </div>
        )}
      </div>
    </div>
  );
}
