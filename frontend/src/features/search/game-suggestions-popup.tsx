import { useState } from "react";

import { SelectIcon } from "../../shared/ui/select-icon";
import {
  hiddenPlatformsLabel,
  suggestionAccessibleName,
  type GameSuggestion,
  type GameSuggestions,
} from "./game-suggestions";

const fallbackCoverUrl = "/assets/covers/fallback.svg";

export type SuggestionsView =
  | { kind: "loading"; rows: number }
  | { kind: "error" }
  | ({ kind: "results" } & GameSuggestions);

type GameSuggestionsPopupProps = {
  activeIndex: number;
  listboxId: string;
  onHover: (index: number) => void;
  onSearchAll: () => void;
  onSelect: (suggestion: GameSuggestion) => void;
  optionId: (index: number) => string;
  term: string;
  view: SuggestionsView;
};

function EnterGlyph() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 16 16">
      <path d="M12.5 3.5v4.25a2 2 0 0 1-2 2H3.5m0 0L6 7.25M3.5 9.75 6 12.25" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.4" />
    </svg>
  );
}

function Chevron({ className }: { className: string }) {
  return (
    <svg aria-hidden="true" className={className} fill="none" viewBox="0 0 16 16">
      <path d="m6 3.5 4.5 4.5L6 12.5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
    </svg>
  );
}

/** A provider cover that fails to load degrades to the product fallback, as on catalogue cards. */
function SuggestionCover({ url }: { url: string }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  return (
    <img
      alt=""
      className="search-suggestion-cover"
      height={52}
      onError={() => setFailedUrl(url)}
      src={failedUrl === url ? fallbackCoverUrl : url}
      width={76}
    />
  );
}

function SuggestionMeta({ suggestion }: { suggestion: GameSuggestion }) {
  const { platforms, hiddenPlatformCount } = suggestion;
  return (
    <span className="search-suggestion-meta">
      {platforms.length === 0 ? null : (
        <>
          <span className="search-suggestion-platforms">
            {platforms.map((platform) => (
              <span className="search-suggestion-platform" key={platform.id} title={platform.name}>
                <SelectIcon name={platform.icon} />
              </span>
            ))}
            {hiddenPlatformCount === 0 ? null : (
              <span className="search-suggestion-platform-more" title={hiddenPlatformsLabel(hiddenPlatformCount)}>
                +{hiddenPlatformCount}
              </span>
            )}
          </span>
          <span className="search-suggestion-divider" />
        </>
      )}
      {suggestion.alias === null ? null : (
        <span className="search-suggestion-alias">También «{suggestion.alias}»</span>
      )}
      <span className="search-suggestion-year">{suggestion.year}</span>
    </span>
  );
}

function announcement(view: SuggestionsView): string {
  if (view.kind === "loading") return "Buscando sugerencias…";
  if (view.kind === "error") return "No se pudieron cargar las sugerencias.";
  if (view.suggestions.length === 0) return "No hay coincidencias.";
  return `${view.suggestions.length} sugerencias disponibles. Usa las flechas para recorrerlas.`;
}

/**
 * The popup of the catalogue-search combobox (#156). Focus never leaves the input: pointer
 * presses inside the popup are prevented from taking it, and the input owns the keyboard.
 */
export function GameSuggestionsPopup({
  activeIndex,
  listboxId,
  onHover,
  onSearchAll,
  onSelect,
  optionId,
  term,
  view,
}: GameSuggestionsPopupProps) {
  const hasActive = view.kind === "results" && view.suggestions[activeIndex] !== undefined;

  return (
    <div
      aria-busy={view.kind === "loading"}
      className="search-suggestions"
      onMouseDown={(event) => event.preventDefault()}
    >
      <div className="search-suggestions-header">
        <span className="search-suggestions-count">
          <Chevron className="search-suggestions-chevron" />
          {view.kind === "loading"
            ? "Buscando…"
            : view.kind === "error"
              ? "Resultados"
              : `Resultados (${view.totalItems})`}
        </span>
        <span aria-hidden="true" className="search-suggestions-hint">
          <span className="search-suggestions-hint-key">Enter</span> para {hasActive ? "abrir" : "buscar"}
          <kbd>
            <EnterGlyph />
          </kbd>
        </span>
        {view.kind === "loading" ? <span className="search-suggestions-progress" /> : null}
      </div>

      {view.kind === "loading" ? (
        <div aria-hidden="true" className="search-suggestions-placeholder">
          {Array.from({ length: view.rows }, (_, index) => (
            <div className="search-suggestion search-suggestion-skeleton" key={index}>
              <span className="search-suggestion-cover skeleton" />
              <span className="search-suggestion-text">
                <span className="skeleton skeleton-title" />
                <span className="skeleton skeleton-meta skeleton-meta-short" />
              </span>
            </div>
          ))}
        </div>
      ) : view.kind === "error" ? (
        <p className="search-suggestions-message search-suggestions-message-error">
          <span aria-hidden="true" className="search-suggestions-message-symbol">!</span>
          No se pudieron cargar las sugerencias
        </p>
      ) : view.suggestions.length === 0 ? (
        <p className="search-suggestions-message">No hay coincidencias</p>
      ) : (
        <ul aria-label="Sugerencias de juegos" className="search-suggestions-list" id={listboxId} role="listbox">
          {view.suggestions.map((suggestion, index) => (
            <li
              aria-label={suggestionAccessibleName(suggestion)}
              aria-selected={index === activeIndex}
              className="search-suggestion"
              id={optionId(index)}
              key={suggestion.gameId}
              onClick={() => onSelect(suggestion)}
              onMouseMove={() => {
                if (index !== activeIndex) onHover(index);
              }}
              role="option"
            >
              <SuggestionCover url={suggestion.cover.url} />
              <span className="search-suggestion-text">
                <span className="search-suggestion-title">{suggestion.title}</span>
                <SuggestionMeta suggestion={suggestion} />
              </span>
              {index === activeIndex ? (
                <span aria-hidden="true" className="search-suggestion-enter">
                  <EnterGlyph />
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      <button className="search-suggestions-all" onClick={onSearchAll} tabIndex={-1} type="button">
        <span aria-hidden="true" className="search-icon" />
        <span className="search-suggestions-all-label">
          Ver todos los resultados para <strong>«{term}»</strong>
        </span>
        <Chevron className="search-suggestions-all-chevron" />
      </button>

      <span className="sr-only" role="status">
        {announcement(view)}
      </span>
    </div>
  );
}
