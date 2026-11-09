import { useEffect } from "react";
import { Link } from "react-router-dom";

import { aboutContent } from "../features/about/about-content";
import { BrandMark } from "../shared/brand/brand-logo";
import { HeroTitle } from "../shared/ui/hero-title";

export function AboutPage() {
  const { title, kicker, greeting, lead, sections, closing, invitation, signOff } = aboutContent;
  useEffect(() => {
    const previousTitle = document.title;
    document.title = `${title} · Gameómetro`;
    return () => { document.title = previousTitle; };
  }, [title]);

  return (
    <article className="page-container community-page">
      <div className="community-reading">
        <header className="community-opening about-opening">
          <BrandMark className="about-mark" />
          <div>
            <p className="community-kicker">{kicker}</p>
            <HeroTitle id="about-title" title={title} />
            <p className="community-lead">
              {greeting.opening}<strong className="about-community-name">{greeting.communityName}</strong>{greeting.continuation}
            </p>
            <p className="community-lead">{lead}</p>
          </div>
        </header>

        {sections.map((section, index) => (
          <section className="about-chapter" key={section.title}>
            <span className="about-chapter-index" aria-hidden="true">{String(index + 1).padStart(2, "0")}</span>
            <div>
              <h2>{section.title}</h2>
              {section.paragraphs.map(paragraph => <p key={paragraph}>{paragraph}</p>)}
            </div>
          </section>
        ))}

        <p className="about-closing">{closing}</p>

        <section className="about-invitation">
          <h2>{invitation.title}</h2>
          <p>{invitation.text}</p>
          <Link className="button button-primary" to="/contacto">
            {invitation.action} <span aria-hidden="true">→</span>
          </Link>
        </section>

        <p className="about-sign-off">
          {signOff.cue}{" "}
          <span lang="en">{signOff.quote}</span>
        </p>
      </div>
    </article>
  );
}
