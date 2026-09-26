import { useQuery } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { listMyRatings, MY_RATINGS_KEY, MyRatingsError, type MyRatingsPage, type MyRatingsQuery } from "../features/ratings/my-ratings-api";
import { MyRatingCard } from "../features/ratings/my-rating-card";
import { useSession } from "../features/session/use-session";
import { AppSelect } from "../shared/ui/app-select";
import { CinematicStage } from "../shared/ui/cinematic-stage";
import { HeroTitle } from "../shared/ui/hero-title";

/**
 * Row-shaped placeholders for the personal collection.
 *
 * <p>They mirror the real maintenance rows rather than the catalogue grid, and stay
 * silent: the page already owns the single live status above them.
 */
function MyRatingsLoading() {
  return <div aria-hidden="true" className="my-ratings-list">
    {[0, 1, 2].map(index => <div className="my-rating-card" key={index}>
      <span className="skeleton my-rating-skeleton-cover" />
      <div className="my-rating-body">
        <span className="skeleton skeleton-title" />
        <span className="skeleton skeleton-meta" />
        <span className="skeleton skeleton-meta skeleton-meta-short" />
      </div>
      <span className="skeleton my-rating-skeleton-score" />
    </div>)}
  </div>;
}

export function MyRatingsPage() {
  const session = useSession();
  const csrfToken = session.data?.authenticated === true ? session.data.csrfToken : null;
  // Personal search and sort are transient; they never enter browser storage or navigation history.
  const [params, setParams] = useState<MyRatingsQuery>({ page: 1, pageSize: 20 });
  const [search, setSearch] = useState("");
  const [inputError, setInputError] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [revision, setRevision] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const query = useQuery<MyRatingsPage, MyRatingsError>({
    queryKey: [...MY_RATINGS_KEY, params],
    queryFn: ({ signal }) => listMyRatings(params, signal),
    enabled: csrfToken !== null, retry: false, staleTime: 0, gcTime: 0,
  });
  function change(next: MyRatingsQuery) {
    setMessage(""); setParams(next); heading.current?.focus();
  }
  function refresh() {
    void query.refetch().then(result => {
      if (result.isSuccess) setRevision(value => value + 1);
    });
  }
  function changed(notice: string) {
    setMessage(notice);
    heading.current?.focus();
  }
  const data = query.data;
  return <section className="hero-page my-ratings-page" aria-labelledby="my-ratings-title">
    <CinematicStage variant="collection" />
    <div className="page-container search-section">
      <div className="hero-heading">
        <p className="eyebrow eyebrow-dot">Tu colección de notas</p>
        <HeroTitle id="my-ratings-title" lead="Mis" accent="puntuaciones" />
      </div>
      {session.isPending ? <p className="result-count" role="status">Comprobando sesión…</p>
        : session.isError ? <div className="notice notice-danger" role="alert">
          <span className="notice-symbol notice-symbol-danger" aria-hidden="true">×</span>
          <p className="notice-kicker">Sesión</p>
          <p>No se pudo comprobar tu sesión.</p><button className="button button-danger" onClick={() => { void session.refetch(); }}>Reintentar</button>
        </div> : csrfToken === null || query.error?.code === "AUTHENTICATION_REQUIRED" ? <div className="notice notice-empty" role="status">
          <span className="notice-symbol" aria-hidden="true">◷</span>
          <h2>Necesitas una sesión activa</h2>
          <p>Inicia sesión al puntuar desde la ficha de un juego para consultar tus notas.</p>
          <Link className="button button-primary" to="/search">Buscar un juego</Link>
        </div> : <>
          <form className="my-ratings-filters" role="search" aria-label="Buscar en mis puntuaciones"
            onSubmit={(event) => {
              event.preventDefault();
              const q = search.trim();
              if ([...q].length > 100 || (q !== "" && !/[\p{L}\p{N}]/u.test(q))) {
                setInputError("Escribe un título o alias de hasta 100 caracteres."); return;
              }
              setInputError(null);
              const next = { ...params, page: 1 };
              if (q) next.q = q; else delete next.q;
              change(next);
            }}>
            <div className="my-ratings-search">
              <label htmlFor="my-ratings-search">Título o alias de tus juegos puntuados</label>
              <div className="my-ratings-search-field">
                <input id="my-ratings-search" type="search" value={search} autoComplete="off"
                  aria-invalid={inputError !== null} aria-describedby={inputError ? "my-ratings-input-error" : undefined}
                  onChange={(event) => setSearch(event.target.value)} />
                <button className="button button-primary" type="submit">
                  Buscar <span className="sr-only">en mis puntuaciones</span>
                </button>
              </div>
            </div>
            <AppSelect
              className="my-ratings-select"
              icon="clock"
              label="Ordenar por"
              onChange={(sort) => {
                if (sort === "updatedAt" || sort === "canonicalTitle" || sort === "ratingValue")
                  change({ ...params, sort, direction: sort === "updatedAt" ? "desc" : "asc", page: 1 });
              }}
              options={[
                { value: "updatedAt", label: "Última actualización", icon: "clock" },
                { value: "canonicalTitle", label: "Título", icon: "title" },
                { value: "ratingValue", label: "Mi nota", icon: "rating" },
              ]}
              value={params.sort ?? "updatedAt"}
            />
            <AppSelect
              className="my-ratings-select"
              icon="descending"
              label="Dirección"
              onChange={(direction) => {
                if (direction === "asc" || direction === "desc") change({ ...params, direction, page: 1 });
              }}
              options={[
                { value: "desc", label: "Descendente", icon: "descending" },
                { value: "asc", label: "Ascendente", icon: "ascending" },
              ]}
              value={params.direction ?? "desc"}
            />
            <AppSelect
              className="my-ratings-select"
              icon="page-size"
              label="Por página"
              onChange={(pageSize) => change({ ...params, pageSize: Number(pageSize), page: 1 })}
              options={[20, 50, 100].map((size) => ({ value: String(size), label: String(size) }))}
              value={String(params.pageSize)}
            />
          </form>
          {inputError ? <p className="my-ratings-input-error" id="my-ratings-input-error" role="alert">{inputError}</p> : null}
          <h2 className="sr-only" tabIndex={-1} ref={heading}>Resultados de mis puntuaciones</h2>
          <p role="status" className="result-count">{message || (query.isFetching ? "Actualizando puntuaciones…" :
            data ? `${data.page.totalItems} puntuaciones · Página ${data.page.number} de ${Math.max(data.page.totalPages, 1)}` : "")}</p>
          {query.isError ? <div className="notice notice-danger" role="alert">
            <span className="notice-symbol notice-symbol-danger" aria-hidden="true">×</span>
            <p className="notice-kicker">Error de carga</p>
            <p>No se pudieron cargar tus puntuaciones. Inténtalo de nuevo.</p>
            <button className="button button-danger" onClick={refresh}>Reintentar carga</button>
          </div> : null}
          {query.isPending ? <MyRatingsLoading />
            : data ? <>
              {data.items.length === 0 ? <div className="notice notice-empty" role="status">
                <span className="notice-symbol" aria-hidden="true"><span className="search-icon" /></span>
                <p className="notice-kicker">Sin resultados</p>
                <h3>{data.page.totalItems > 0 ? "Esta página ya no tiene resultados" :
                  params.q ? "No hay puntuaciones que coincidan con tu búsqueda" : "Todavía no has puntuado ningún juego"}</h3>
                {params.q ? <button className="button button-primary" onClick={() => {
                  setSearch(""); const next = { ...params, page: 1 }; delete next.q; change(next);
                }}>Limpiar búsqueda</button>
                  : <Link className="button button-primary" to="/search">Explorar el catálogo</Link>}
              </div> : <div className="my-ratings-list">
                {data.items.map(item => <MyRatingCard key={`${item.game.gameId}:${revision}`} item={item} csrfToken={csrfToken} onChanged={changed} />)}
              </div>}
              <nav className="pagination my-ratings-pagination" aria-label="Paginación de mis puntuaciones">
                {data.page.number > 1 ? <button className="button view-action view-action-back" onClick={() =>
                  change({ ...params, page: Math.max(1, Math.min(data.page.number - 1, data.page.totalPages)) })}>
                  <span aria-hidden="true">←</span> Página anterior
                </button> : null}
                {data.page.number < data.page.totalPages ? <button className="button view-action" onClick={() =>
                  change({ ...params, page: data.page.number + 1 })}>
                  Página siguiente <span aria-hidden="true">→</span>
                </button> : null}
                <button className="button my-ratings-refresh" onClick={refresh} disabled={query.isFetching}>Actualizar resultados</button>
              </nav>
            </> : null}
        </>}
    </div>
  </section>;
}
