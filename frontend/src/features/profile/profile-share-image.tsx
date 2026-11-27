import { skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import { canShareImages, saveBlob } from "../../shared/share-image/canvas-kit";
import { AppSelect, type AppSelectOption } from "../../shared/ui/app-select";
import { MoreInfo } from "./more-info";
import { ProfileError } from "./profile-api";
import {
  blockLabels,
  blockUnavailability,
  cardFileName,
  cardSections,
  describeCard,
  SHARE_FORMATS,
  SHARE_MESSAGE,
  shareText,
  toProfileCard,
  type GamesOrder,
  type ProfileCard,
  type ShareBlock,
  type ShareChoices,
  type ShareFormat,
} from "./profile-share-image-model";
import { exportProfileCard, loadCardAssets, paintProfileCard, type CardAssets } from "./profile-share-image-painter";
import {
  getPublicProfile,
  getPublicProfileSection,
  getPublicProfileStatistics,
  PUBLIC_PROFILES_KEY,
  publicProfilePath,
} from "./public-profile-api";

const BLOCKS: readonly ShareBlock[] = ["games", "scoring", "wishlist"];
const FORMATS: readonly ShareFormat[] = ["post", "story"];

const blockHints: Record<ShareBlock, string> = {
  games: "La portada, el título y tu nota de hasta tres juegos.",
  scoring: "Cuántos juegos has puntuado, tu media y el reparto de tus notas.",
  wishlist: "Cuántos juegos deseas y las portadas de los primeros.",
};

const gameOrders = [
  { value: "top", label: "Notas más altas", icon: "descending" },
  { value: "recent", label: "Notas recientes", icon: "clock" },
] as const satisfies readonly (AppSelectOption & { value: GamesOrder })[];

/** A visitor's read: the studio uses the same public responses and never a private one. */
const publicRead = { gcTime: 0, staleTime: 0, retry: false } as const;

type Outcome = { tone: "status" | "alert"; text: string; link?: string } | null;
type Check = "public" | "withdrawn" | "changed" | "failed";

function ImageIcon() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
      <rect height="13" rx="2" stroke="currentColor" strokeWidth="1.5" width="15" x="2.5" y="3.5" />
      <circle cx="7" cy="8" r="1.5" fill="currentColor" />
      <path d="m3.5 15 4.25-4.25 2.75 2.75 2.5-2.5 3.5 3.5" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
    </svg>
  );
}

/**
 * **Crear imagen para compartir** (#619): a disclosure in **Mi perfil → Perfil público** that turns
 * the published profile into a branded image. Nothing is read until the player opens it, the
 * preview is built only from the public responses a visitor receives, and the profile is read
 * again before any image leaves the page, so a withdrawn profile never produces one.
 */
export function ProfileShareImage({ alias }: { alias: string }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  return (
    <div className="share-card">
      <button
        aria-controls={`${id}-studio`}
        aria-expanded={open}
        className="button share-card-toggle"
        onClick={() => setOpen(!open)}
        type="button"
      >
        <ImageIcon />
        Crear imagen para compartir
        <span aria-hidden="true" className="share-card-chevron" />
      </button>
      <div className="share-card-studio" hidden={!open} id={`${id}-studio`}>
        {open ? <ShareStudio alias={alias} /> : null}
      </div>
    </div>
  );
}

function ShareStudio({ alias }: { alias: string }) {
  const id = useId();
  const client = useQueryClient();
  const [choices, setChoices] = useState<ShareChoices>({
    games: true, gamesOrder: "top", scoring: true, wishlist: true, format: "post",
  });
  const [assets, setAssets] = useState<CardAssets | null>(null);
  const [painted, setPainted] = useState<ProfileCard | null>(null);
  const [failed, setFailed] = useState<ProfileCard | null>(null);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<Outcome>(null);
  const [shareable] = useState(canShareImages);
  const canvas = useRef<HTMLCanvasElement>(null);
  const url = new URL(publicProfilePath(alias), window.location.origin).href;

  const profileKey = [...PUBLIC_PROFILES_KEY, alias] as const;
  const profile = useQuery({
    queryKey: profileKey, queryFn: ({ signal }) => getPublicProfile(alias, signal), ...publicRead,
  });
  const sections = profile.isSuccess ? profile.data.sections : null;
  const ratings = sections?.includes("ratings") ?? false;
  const wishlist = sections?.includes("wishlist") ?? false;
  const sort = choices.gamesOrder === "top" ? "scoreDesc" : "ratingDate";
  const statistics = useQuery({
    queryKey: [...profileKey, "statistics"],
    queryFn: sections === null ? skipToken : ({ signal }) => getPublicProfileStatistics(alias, signal),
    ...publicRead,
  });
  const rated = useQuery({
    queryKey: [...profileKey, "share-image", "ratings", sort],
    queryFn: ratings ? ({ signal }) => getPublicProfileSection(alias, "ratings", 1, signal, sort, 6) : skipToken,
    ...publicRead,
  });
  const wished = useQuery({
    queryKey: [...profileKey, "share-image", "wishlist"],
    queryFn: wishlist ? ({ signal }) => getPublicProfileSection(alias, "wishlist", 1, signal, undefined, 6) : skipToken,
    ...publicRead,
  });

  const loaded = sections !== null && statistics.isSuccess
    && (!ratings || rated.isSuccess) && (!wishlist || wished.isSuccess);
  const source = useMemo(() => loaded ? {
    alias,
    sections: sections ?? [],
    statistics: statistics.data ?? null,
    ratedGames: ratings ? rated.data?.items ?? null : null,
    wishedGames: wishlist ? wished.data?.items ?? null : null,
  } : null, [loaded, alias, sections, statistics.data, ratings, rated.data, wishlist, wished.data]);
  const card = useMemo(() => source === null ? null : toProfileCard(source, choices, url), [source, choices, url]);
  const ready = card !== null && painted === card;

  useEffect(() => {
    let active = true;
    void loadCardAssets().then((loadedAssets) => { if (active) setAssets(loadedAssets); });
    return () => { active = false; };
  }, []);

  // Painting synchronizes the chosen card with the canvas, an external drawing surface; a paint
  // superseded by a newer choice never reaches it.
  useEffect(() => {
    let current = true;
    if (card !== null && assets !== null && canvas.current !== null) {
      void paintProfileCard(canvas.current, card, assets, () => current).then((done) => {
        if (!current) return;
        if (done) setPainted(card);
        else setFailed(card);
      });
    }
    return () => { current = false; };
  }, [card, assets]);

  /** The profile read again, as a visitor, right before an image is produced. */
  async function confirmPublic(shown: ProfileCard): Promise<Check> {
    try {
      const fresh = await client.fetchQuery({
        queryKey: profileKey, queryFn: ({ signal }) => getPublicProfile(alias, signal), staleTime: 0,
      });
      if (!cardSections(shown).every((section) => fresh.sections.includes(section))) {
        void client.invalidateQueries({ queryKey: profileKey });
        return "changed";
      }
      return "public";
    } catch (error) {
      return error instanceof ProfileError && error.status === 404 ? "withdrawn" : "failed";
    }
  }

  async function produce(): Promise<File | null> {
    if (card === null || canvas.current === null) return null;
    const shown = card;
    setOutcome({ tone: "status", text: "Comprobando que tu perfil sigue siendo público…" });
    const check = await confirmPublic(shown);
    if (check === "withdrawn") {
      setOutcome({ tone: "status", text: "Tu perfil ya no es público, así que no se ha creado ninguna imagen." });
      return null;
    }
    if (check === "changed") {
      setOutcome({ tone: "status", text: "Lo que compartes en tu perfil ha cambiado. Revisa la vista previa antes de compartirla." });
      return null;
    }
    if (check === "failed") {
      setOutcome({ tone: "alert", text: "No se pudo comprobar tu perfil, así que no se ha creado ninguna imagen. Inténtalo de nuevo." });
      return null;
    }
    const blob = await exportProfileCard(canvas.current);
    if (blob === null) {
      setOutcome({ tone: "alert", text: "No se pudo crear la imagen en este navegador. Inténtalo de nuevo o prueba con otro navegador." });
      return null;
    }
    return new File([blob], cardFileName(shown), { type: "image/png" });
  }

  async function run(action: (file: File) => void | Promise<void>) {
    if (busy) return;
    setBusy(true);
    try {
      const file = await produce();
      if (file !== null) await action(file);
    } finally {
      setBusy(false);
    }
  }

  async function share(file: File) {
    if (typeof navigator.canShare !== "function" || !navigator.canShare({ files: [file] })) {
      download(file);
      return;
    }
    try {
      await navigator.share({ files: [file], title: `@${alias} · Gameómetro`, text: shareText({ url }) });
      setOutcome({ tone: "status", text: "Imagen enviada a la aplicación que elegiste." });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        setOutcome(null);
        return;
      }
      setOutcome({ tone: "alert", text: "No se pudo compartir la imagen. Descárgala y compártela desde tus archivos." });
    }
  }

  function download(file: File) {
    saveBlob(file, file.name);
    setOutcome({ tone: "status", text: `Imagen descargada como ${file.name}. Compártela junto al enlace de tu perfil.` });
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(shareText({ url }));
      setOutcome({ tone: "status", text: "Mensaje y enlace copiados. Pégalos junto a la imagen." });
    } catch {
      setOutcome({ tone: "status", text: `${SHARE_MESSAGE} Copia este enlace:`, link: url });
    }
  }

  function choose(change: Partial<ShareChoices>) {
    setOutcome(null);
    setChoices({ ...choices, ...change });
  }

  function toggleBlock(block: ShareBlock, shown: boolean) {
    const next = { ...choices };
    next[block] = shown;
    setOutcome(null);
    setChoices(next);
  }

  const unavailable = profile.error instanceof ProfileError && profile.error.status === 404
    || statistics.error instanceof ProfileError && statistics.error.status === 404;
  const readFailed = !unavailable && (profile.isError || statistics.isError || rated.isError || wished.isError);
  const { width, height } = SHARE_FORMATS[choices.format];

  if (unavailable) {
    return (
      <div className="notice notice-info share-card-notice" role="status">
        <p className="notice-kicker">Imagen del perfil</p>
        <p>Tu perfil no es público ahora mismo, así que no se puede crear ninguna imagen de él.</p>
      </div>
    );
  }

  return (
    <section aria-labelledby={`${id}-title`} className="share-card-layout">
      <h3 className="share-card-title" id={`${id}-title`}>Tu perfil en una imagen</h3>
      <div className="share-card-preview" data-format={choices.format} data-ready={ready || undefined}>
        <canvas
          aria-label={painted === null ? "Vista previa de la imagen, en preparación" : describeCard(painted)}
          className="share-card-canvas"
          height={height}
          ref={canvas}
          role="img"
          width={width}
        />
        {readFailed ? null : !ready && failed !== card ? (
          <p className="share-card-wait" role="status">Preparando la vista previa…</p>
        ) : null}
      </div>
      <div className="share-card-controls">
        {readFailed ? (
          <div className="notice notice-danger" role="alert">
            <p>No se pudo leer tu perfil público para preparar la imagen.</p>
            <button className="button" onClick={() => {
              void client.invalidateQueries({ queryKey: profileKey });
            }} type="button">Reintentar</button>
          </div>
        ) : null}
        {failed !== null && failed === card ? (
          <p className="profile-feedback profile-feedback-error" role="alert">
            Este navegador no puede dibujar la imagen. Prueba con otro navegador.
          </p>
        ) : null}
        <fieldset className="share-card-fieldset" disabled={source === null || busy}>
          <legend>Qué aparece</legend>
          {BLOCKS.map((block) => {
            const reason = source === null ? null : blockUnavailability(source, block);
            const hint = `${id}-${block}-hint`;
            return (
              <div className="share-card-option" key={block}>
                <label>
                  <input
                    aria-describedby={hint}
                    checked={choices[block] && reason === null}
                    disabled={reason !== null}
                    onChange={(event) => toggleBlock(block, event.target.checked)}
                    type="checkbox"
                  />
                  {blockLabels[block]}
                </label>
                <p className="share-card-hint" id={hint}>{reason ?? blockHints[block]}</p>
                {block === "games" && reason === null ? (
                  <AppSelect
                    className="share-card-order"
                    disabled={!choices.games || source === null || busy}
                    icon="descending"
                    label="Qué juegos"
                    onChange={(value) => {
                      const order = gameOrders.find((option) => option.value === value);
                      if (order) choose({ gamesOrder: order.value });
                    }}
                    options={gameOrders}
                    value={choices.gamesOrder}
                  />
                ) : null}
              </div>
            );
          })}
        </fieldset>
        <fieldset className="share-card-fieldset share-card-formats" disabled={busy}>
          <legend>Formato</legend>
          {FORMATS.map((format) => (
            <label key={format}>
              <input
                checked={choices.format === format}
                name={`${id}-format`}
                onChange={() => choose({ format })}
                type="radio"
              />
              <span>{SHARE_FORMATS[format].label}</span>
              <span className="share-card-ratio">{SHARE_FORMATS[format].ratio}</span>
            </label>
          ))}
        </fieldset>
        <MoreInfo about="sobre la imagen">
          <p>
            La imagen se crea en tu navegador solo con lo que ya muestra tu perfil público, y Gameómetro
            no la guarda. Quien la reciba podrá conservarla y reenviarla aunque después cambies tu
            perfil o lo hagas privado.
          </p>
        </MoreInfo>
        <div className="share-card-actions">
          {shareable ? (
            <button className="button button-primary" disabled={!ready || busy} onClick={() => void run(share)} type="button">
              Compartir imagen
            </button>
          ) : null}
          <button
            className={shareable ? "button" : "button button-primary"}
            disabled={!ready || busy}
            onClick={() => void run(download)}
            type="button"
          >
            Descargar imagen
          </button>
          {shareable ? null : (
            <button className="button" disabled={busy} onClick={() => void copyLink()} type="button">Copiar enlace</button>
          )}
        </div>
        <div className="share-card-outcome">
          {outcome?.tone === "alert" ? <p className="profile-feedback profile-feedback-error" role="alert">{outcome.text}</p> : null}
          <p role="status">
            {outcome?.tone === "status" ? outcome.text : ""}
            {outcome?.link ? <> <a className="text-link" href={outcome.link}>{outcome.link}</a></> : null}
          </p>
        </div>
      </div>
    </section>
  );
}
