import { useQuery } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { listMyRatings, MY_RATINGS_KEY, MyRatingsError, type MyRatingsPage, type MyRatingsQuery } from "../features/ratings/my-ratings-api";
import { MyRatingCard } from "../features/ratings/my-rating-card";
import { useSession } from "../features/session/use-session";
import { CatalogueLoading } from "../shared/ui/catalogue-loading";

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
  return <section className="page-container search-section" aria-labelledby="my-ratings-title">
    <p className="eyebrow eyebrow-dot">Tu colección de notas</p>
    <h1 className="page-title" id="my-ratings-title">Mis puntuaciones</h1>
    <p className="search-intro">Consulta y mantén tus puntuaciones de juegos.</p>
    {session.isPending ? <p role="status">Comprobando sesión…</p>
      : session.isError ? <div className="notice notice-error" role="alert">
        <p>No se pudo comprobar tu sesión.</p><button className="button" onClick={() => { void session.refetch(); }}>Reintentar</button>
      </div> : csrfToken === null || query.error?.code === "AUTHENTICATION_REQUIRED" ? <div className="notice notice-empty" role="status">
        <h2>Necesitas una sesión activa</h2>
        <p>Inicia sesión al puntuar desde la ficha de un juego para consultar tus notas.</p>
        <Link className="button" to="/search">Buscar un juego</Link>
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
            <input id="my-ratings-search" type="search" value={search} autoComplete="off"
              aria-invalid={inputError !== null} aria-describedby={inputError ? "my-ratings-input-error" : undefined}
              onChange={(event) => setSearch(event.target.value)} />
          </div>
          <button className="button button-primary" type="submit">Buscar en mis puntuaciones</button>
          <label>Ordenar por
            <select value={params.sort ?? "updatedAt"} onChange={(event) => {
              const sort = event.target.value;
              if (sort === "updatedAt" || sort === "canonicalTitle" || sort === "ratingValue")
                change({ ...params, sort, direction: sort === "updatedAt" ? "desc" : "asc", page: 1 });
            }}>
              <option value="updatedAt">Última actualización</option><option value="canonicalTitle">Título</option><option value="ratingValue">Mi nota</option>
            </select>
          </label>
          <label>Dirección
            <select value={params.direction ?? "desc"} onChange={(event) => {
              const direction = event.target.value;
              if (direction === "asc" || direction === "desc") change({ ...params, direction, page: 1 });
            }}>
              <option value="desc">Descendente</option><option value="asc">Ascendente</option>
            </select>
          </label>
          <label>Por página
            <select value={params.pageSize} onChange={(event) => change({ ...params, pageSize: Number(event.target.value), page: 1 })}>
              <option value={20}>20</option><option value={50}>50</option><option value={100}>100</option>
            </select>
          </label>
        </form>
        {inputError ? <p id="my-ratings-input-error" role="alert">{inputError}</p> : null}
        <h2 className="sr-only" tabIndex={-1} ref={heading}>Resultados de mis puntuaciones</h2>
        <p role="status" className="results-summary">{message || (query.isFetching ? "Actualizando puntuaciones…" :
          data ? `${data.page.totalItems} puntuaciones · Página ${data.page.number} de ${Math.max(data.page.totalPages, 1)}` : "")}</p>
        {query.isError ? <div className="notice notice-error" role="alert">
          <p>No se pudieron cargar tus puntuaciones. Inténtalo de nuevo.</p>
          <button className="button" onClick={refresh}>Reintentar carga</button>
        </div> : null}
        {query.isPending ? <CatalogueLoading message="Cargando tus puntuaciones…" />
          : data ? <>
            {data.items.length === 0 ? <div className="notice notice-empty" role="status">
              <h3>{data.page.totalItems > 0 ? "Esta página ya no tiene resultados" :
                params.q ? "No hay puntuaciones que coincidan con tu búsqueda" : "Todavía no has puntuado ningún juego"}</h3>
              {params.q ? <button className="button" onClick={() => {
                setSearch(""); const next = { ...params, page: 1 }; delete next.q; change(next);
              }}>Limpiar búsqueda</button>
                : <Link className="button" to="/search">Explorar el catálogo</Link>}
            </div> : <div className="my-ratings-list">
              {data.items.map(item => <MyRatingCard key={`${item.game.gameId}:${revision}`} item={item} csrfToken={csrfToken} onChanged={changed} />)}
            </div>}
            <nav className="pagination" aria-label="Paginación de mis puntuaciones">
              {data.page.number > 1 ? <button className="button" onClick={() =>
                change({ ...params, page: Math.max(1, Math.min(data.page.number - 1, data.page.totalPages)) })}>Página anterior</button> : null}
              {data.page.number < data.page.totalPages ? <button className="button" onClick={() =>
                change({ ...params, page: data.page.number + 1 })}>Página siguiente</button> : null}
              <button className="button" onClick={refresh} disabled={query.isFetching}>Actualizar resultados</button>
            </nav>
          </> : null}
      </>}
  </section>;
}
