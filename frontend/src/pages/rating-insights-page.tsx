import { useQuery } from "@tanstack/react-query";
import { Link, useLoaderData } from "react-router-dom";
import { getRatingInsights, RATING_INSIGHTS_KEY, type RatingExtreme, type RatingInsights, type RatingInsightsError } from "../features/ratings/rating-insights-api";
import { GenreInsights, RatingActivity, RatingDistribution } from "../features/ratings/rating-insights-charts";
import { number, decimal } from "../features/ratings/rating-insights-format";
import { authenticationStartUrl } from "../features/session/auth-entry";
import { useSession } from "../features/session/use-session";
import { GameMeter } from "../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../shared/score/thermal-band";
import { CatalogueCover } from "../shared/ui/catalogue-cover";
import { HeroTitle } from "../shared/ui/hero-title";
import { StageLight } from "../shared/ui/stage-light";
import { useGameLinkState } from "../shared/navigation/use-game-link-state";

function ExtremeGame({ extreme, label }: { extreme: RatingExtreme; label: string }) {
  const linkState = useGameLinkState();
  const { game } = extreme;
  const band = thermalBand(extreme.value);
  const cover = "attribution" in game.primaryCover
    ? { ...game.primaryCover, kind: "provider" as const }
    : { ...game.primaryCover, kind: "fallback" as const };
  return <article className="insights-extreme" aria-label={label}>
    <CatalogueCover cover={cover} caption={false} />
    <div className="insights-extreme-copy">
      <p className="insights-caption">{label}</p>
      <p className="insights-extreme-value" data-thermal={band ?? undefined}><strong>{extreme.value}</strong><span>/10 <span>{band ? thermalLabels[band] : ""}</span></span></p>
      <h3><Link to={`/games/${game.gameId}/${game.slug}`} state={linkState}>{game.canonicalTitle}</Link></h3>
      {extreme.tiedGames > 1 ? <p className="insights-note">{number(extreme.tiedGames)} juegos comparten esta puntuación.</p> : null}
    </div>
  </article>;
}

function InsightsContent({ data }: { data: RatingInsights }) {
  const band = data.mean === null ? null : thermalBand(data.mean);
  const coincident = data.highest?.value === data.lowest?.value;
  return <>
    <div className="insights-opening">
      <div className="insights-collection">
        <p className="insights-caption">Tu colección, vista de otra forma</p>
        <p className="insights-total"><strong>{number(data.total)}</strong><span>{data.total === 1 ? "juego puntuado" : "juegos puntuados"}</span></p>
        <p className="insights-intro">Cada juego deja una huella.<br />Estas son las tuyas.</p>
        {data.total < 5 ? <p className="insights-note insights-small-sample">Tu colección acaba de empezar. Con menos de cinco juegos, estas cifras describen una muestra pequeña.</p> : null}
      </div>
      <div className="insights-instrument" data-thermal={band ?? undefined}>
        <GameMeter className="insights-meter" value={data.mean} />
        <div className="insights-mean"><p className="insights-caption">Tu puntuación media</p><p><strong>{data.mean === null ? "—" : decimal(data.mean)}</strong><span> /10</span></p>
          <p className="insights-temperature">{band ? thermalLabels[band] : "Sin puntuaciones"}</p></div>
      </div>
    </div>
    <RatingDistribution distribution={data.distribution} total={data.total} />
    <section className="insights-extremes" aria-labelledby="extremes-title">
      <div className="insights-section-head"><div><p className="insights-caption">Los extremos de tu escala</p><h2 id="extremes-title">{coincident ? "Una misma puntuación" : "De un juego a otro"}</h2></div>
        <p>{coincident ? "Todas tus puntuaciones coinciden." : "Tus dos extremos. El mismo criterio, distintas temperaturas."}</p></div>
      <div className="insights-extreme-pair">
        {data.highest ? <ExtremeGame extreme={data.highest} label={coincident ? "Tu puntuación común" : "Tu puntuación más alta"} /> : null}
        {data.lowest && !coincident ? <ExtremeGame extreme={data.lowest} label="Tu puntuación más baja" /> : null}
      </div>
      {(data.highest?.tiedGames ?? 0) > 1 || (data.lowest?.tiedGames ?? 0) > 1 ? <p className="insights-note">Si hay empate, se muestra un juego elegido por su identificador estable, sin orden de preferencia.</p> : null}
    </section>
    <div className="insights-exploration"><GenreInsights {...data} /><RatingActivity activity={data.activity} olderRatings={data.olderRatings} /></div>
    <details className="insights-method insights-calculation"><summary>Cómo se leen tus estadísticas</summary>
      <p>Solo cuentan tus puntuaciones actuales. Cada juego pesa lo mismo en la media, redondeada a un decimal. Las temperaturas leen la puntuación que ves: Congelado (1–2), Frío (más de 2–4), Templado (más de 4–6), Caliente (más de 6–8) y Ardiendo (más de 8–10).</p>
      <p>En géneros, los empates por cantidad se resuelven por el identificador del género. Por media, se compara primero la media sin redondear, después la muestra y por último el identificador. Un grupo pequeño no demuestra una preferencia.</p>
      <p>Las cifras cambian al editar o eliminar tus puntuaciones. No mezclan puntuaciones de la comunidad ni de fuentes externas.</p>
    </details>
  </>;
}

export function RatingInsightsPage() {
  const initial = useLoaderData<{ data: RatingInsights; sessionProof: string } | null>();
  const session = useSession();
  const proof = session.data?.authenticated === true ? session.data.csrfToken : null;
  const query = useQuery<RatingInsights, RatingInsightsError>({
    // Session-bound CSRF material scopes ephemeral document memory; it is never persisted,
    // sent as identity, or used for telemetry. A changed session cannot render the old answer.
    queryKey: [...RATING_INSIGHTS_KEY, proof],
    queryFn: ({ signal }) => getRatingInsights(signal),
    enabled: proof !== null && !session.isFetching,
    ...(initial?.sessionProof === proof ? {initialData:initial.data} : {}),
    refetchOnMount: false,
    gcTime: 0, staleTime: initial?.sessionProof === proof ? Infinity : 0, retry: false,
  });
  const signedOut = proof === null || query.error?.code === "AUTHENTICATION_REQUIRED";
  const data = !session.isFetching && !session.isError && !signedOut && !query.isError ? query.data : undefined;
  const lights = [data?.highest, data?.lowest].flatMap(extreme => extreme && "attribution" in extreme.game.primaryCover ? [extreme.game.primaryCover.url] : []);
  return <section className="hero-page insights-page" aria-labelledby="insights-title">
    <StageLight images={lights} />
    <div className="page-container">
      <header className="page-head insights-page-head"><div><Link className="insights-return" to="/mis-puntuaciones">Mis puntuaciones</Link><HeroTitle id="insights-title" title="Tus estadísticas" /></div>
        {data && data.total > 0 ? <p className="insights-note">Solo tus puntuaciones<br /><time dateTime={data.evaluatedOn}>{new Intl.DateTimeFormat("es-ES", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(`${data.evaluatedOn}T12:00:00Z`))}</time></p> : null}</header>
      {session.isPending || session.isFetching ? <p className="result-count" role="status">Comprobando sesión…</p>
        : session.isError ? <div className="notice notice-danger" role="alert"><h2>No se pudo comprobar tu sesión</h2><button className="button button-danger" onClick={() => { void session.refetch(); }}>Reintentar</button></div>
        : signedOut ? <div className="notice notice-empty" role="status"><p className="notice-kicker">Tu espacio personal</p><h2>Necesitas una sesión activa</h2><p>Inicia sesión para descubrir tus estadísticas.</p><a className="button button-primary" href={authenticationStartUrl("/mis-puntuaciones/estadisticas")}>Iniciar sesión</a></div>
          : query.isPending ? <><p className="result-count" role="status">Leyendo tus puntuaciones…</p><div className="insights-loading" aria-hidden="true"><span className="skeleton" /><span className="skeleton" /></div></>
            : query.isError ? <div className="notice notice-danger" role="alert"><p className="notice-kicker">Error de carga</p><h2>No se pudieron cargar tus estadísticas</h2><p>Vuelve a intentarlo para consultar tus puntuaciones.</p>{query.error.correlationId ? <p className="notice-reference">Referencia: {query.error.correlationId}</p> : null}<button className="button button-danger" onClick={() => { void query.refetch(); }}>Reintentar carga</button><Link className="button" to="/mis-puntuaciones">Mis puntuaciones</Link></div>
              : data?.total === 0 ? <div className="notice notice-empty insights-empty" role="status"><p className="notice-kicker">Tu próxima partida cuenta</p><h2>Tu historia empieza con una puntuación</h2><p>Busca un juego que conozcas y puntúalo. Aquí verás cómo va tomando forma tu colección.</p><Link className="button button-primary" to="/search">Buscar juegos</Link><Link className="button" to="/mis-puntuaciones">Mis puntuaciones</Link></div>
                : data ? <><p className="sr-only" role="status">{query.isFetching ? "Actualizando estadísticas…" : "Estadísticas personales cargadas"}</p><InsightsContent key={data.evaluatedOn} data={data} /></> : null}
    </div>
  </section>;
}
