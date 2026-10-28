import { Component, type ReactNode } from "react";
import { Link } from "react-router-dom";

import { HeroTitle } from "../../shared/ui/hero-title";

/** The home's `h1` and one line on what it holds, compact above the week's release. */
export function HomeIntro() {
  return (
    <header className="home-intro">
      <HeroTitle id="home-title" title="Descubre tu próximo juego" />
      <p className="home-lead">Lanzamientos, destacados y los juegos mejor valorados.</p>
    </header>
  );
}

type HomeSectionHeadProps = {
  id: string;
  /** The section's `h2`; a lockup may stand in for words when it carries its own name. */
  title: ReactNode;
  lead?: string | undefined;
  /** What the preview reads, in the instrument's readout voice, such as the days it covers. */
  readout?: ReactNode;
  /** The full destination this preview leads to. */
  action?: { label: string; to: string } | undefined;
};

/** A preview's opening: its name, one line on what it offers and the way to the full feature. */
export function HomeSectionHead({ id, title, lead, readout, action }: HomeSectionHeadProps) {
  return (
    <div className="home-section-head">
      <div className="home-section-intro">
        <h2 className="home-section-title" id={id}>
          {title}
        </h2>
        {lead === undefined && readout === undefined ? null : (
          <p className="home-section-lead">
            {lead}
            {readout === undefined ? null : <span className="home-section-readout">{readout}</span>}
          </p>
        )}
      </div>
      {action === undefined ? null : (
        <Link className="home-section-action view-action" to={action.to}>
          {action.label} <span aria-hidden="true">→</span>
        </Link>
      )}
    </div>
  );
}

export type HomeNoticeTone = "info" | "empty" | "warning" | "danger";

type HomeNoticeProps = {
  tone: HomeNoticeTone;
  kicker: string;
  title: string;
  children?: ReactNode;
  /** A failed read's support reference, shown as the identifier it is. */
  correlationId?: string | null;
  actions?: ReactNode;
};

/**
 * One preview's own state, in the shared notice language at the size of a preview: it replaces
 * only that preview, owns its recovery and its way out, and never blocks the rest of the home.
 */
export function HomeNotice({ tone, kicker, title, children, correlationId = null, actions }: HomeNoticeProps) {
  return (
    <div className={`notice notice-${tone} home-notice`} role={tone === "danger" ? "alert" : undefined}>
      <p className="notice-kicker">{kicker}</p>
      <h3>{title}</h3>
      {children}
      {actions === undefined ? null : <div className="notice-actions">{actions}</div>}
      {correlationId === null ? null : (
        <p className="notice-reference">Referencia para soporte: {correlationId}</p>
      )}
    </div>
  );
}

type PreviewBoundaryProps = { name: string; children: ReactNode };

/**
 * Keeps an unexpected rendering fault inside one preview: the rest of the home stays usable and the
 * preview says, in place, that it is unavailable. Read failures are states, handled by each preview.
 */
export class PreviewBoundary extends Component<PreviewBoundaryProps, { failed: boolean }> {
  override state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  override render() {
    if (!this.state.failed) return this.props.children;
    return (
      <HomeNotice kicker="No disponible" title={`${this.props.name} no está disponible ahora mismo`} tone="danger">
        <p>El resto de Gameómetro sigue funcionando. Prueba a recargar la página más tarde.</p>
      </HomeNotice>
    );
  }
}
