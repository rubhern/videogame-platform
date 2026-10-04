import type { GameDetails } from "./game-details-api";
import { GameReleaseList } from "./game-release-list";

type Fact = "credits" | "publishers" | "genres" | "modes";

/** Decorative marks for each fact, drawn in the stroke of the shared selector icons. */
function FactIcon({ fact }: { fact: Fact }) {
  return (
    <svg aria-hidden="true" className="game-fact-icon" fill="none" viewBox="0 0 20 20">
      {fact === "credits" ? (
        <>
          <path d="m7 6-4 4 4 4M13 6l4 4-4 4" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
          <path d="m11.2 4.5-2.4 11" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
        </>
      ) : fact === "publishers" ? (
        <>
          <path d="M3.5 16.5h13M5 16.5V7.2L10 4l5 3.2v9.3" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
          <path d="M8 9.5h4M8 12.5h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
        </>
      ) : fact === "genres" ? (
        <>
          <path d="M3.5 4.8v4.4c0 .4.2.8.5 1.1l5.9 5.9a1.5 1.5 0 0 0 2.1 0l4.1-4.1a1.5 1.5 0 0 0 0-2.1L10.2 4a1.6 1.6 0 0 0-1.1-.5H4.8c-.7 0-1.3.6-1.3 1.3Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
          <circle cx="7.2" cy="7.2" r="1.2" fill="currentColor" />
        </>
      ) : (
        <>
          <circle cx="7.5" cy="7" r="2.5" stroke="currentColor" strokeWidth="1.5" />
          <path d="M2.8 15.8c.6-2.4 2.4-3.8 4.7-3.8s4.1 1.4 4.7 3.8" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
          <path d="M13 4.8a2.4 2.4 0 0 1 0 4.4M14.5 12.3c1.3.5 2.3 1.7 2.7 3.5" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
        </>
      )}
    </svg>
  );
}

type Company = GameDetails["developers"][number];
type Credit = { fact: Fact; label: string; companies: readonly Company[] };
type Group = { fact: Fact; label: string; values: readonly { id: string; name: string }[] };

const names = new Intl.ListFormat("es", { style: "long", type: "conjunction" });

/**
 * One information section: distinct company roles and catalogue taxonomy, followed by releases.
 * Unknown metadata is omitted; known names retain the catalogue's spelling and language.
 */
export function GameInformation({ game }: { game: GameDetails }) {
  const allCredits: Credit[] = [
    { fact: "credits", label: "Desarrollador", companies: game.developers },
    { fact: "publishers", label: "Publisher", companies: game.publishers },
  ];
  const allGroups: Group[] = [
    {
      fact: "genres",
      label: "Géneros",
      values: game.genres.map((genre) => ({ id: genre.genreId, name: genre.name })),
    },
    {
      fact: "modes",
      label: "Modos de juego",
      values: game.gameModes.map((mode) => ({ id: mode.gameModeId, name: mode.name })),
    },
  ];
  const credits = allCredits.filter((credit) => credit.companies.length > 0);
  const groups = allGroups.filter((group) => group.values.length > 0);

  return (
    <section
      className="game-panel game-information game-detail-facts"
      aria-labelledby="game-information-title"
    >
      <h2 className="game-panel-title" id="game-information-title">
        Información del juego
      </h2>
      {credits.length > 0 || groups.length > 0 ? (
        <dl className="game-facts">
          {credits.map((credit) => (
            <div className="game-fact game-fact-credit" key={credit.label}>
              <dt>
                <FactIcon fact={credit.fact} />
                {credit.label}
              </dt>
              <dd>{names.format(credit.companies.map((company) => company.name))}</dd>
            </div>
          ))}
          {groups.map((group) => (
            <div className="game-fact" key={group.label}>
              <dt>
                <FactIcon fact={group.fact} />
                {group.label}
              </dt>
              <dd>
                <ul className="game-chips">
                  {group.values.map((value) => (
                    <li className="game-chip" key={value.id}>
                      {value.name}
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
          ))}
        </dl>
      ) : null}
      <GameReleaseList releases={game.releases} />
    </section>
  );
}
