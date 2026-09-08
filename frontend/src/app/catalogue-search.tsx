import { useEffect, useId, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import {
  countCodePoints,
  DEFAULT_PAGE_SIZE,
  gameSearchPath,
  MAX_QUERY_CODE_POINTS,
  readGameSearchParams,
} from "../features/search/game-search-params";

export function CatalogueSearch() {
  const location = useLocation();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const inputId = useId();
  const errorId = `${inputId}-error`;
  const routeQuery =
    location.pathname === "/search"
      ? readGameSearchParams(new URLSearchParams(location.search)).query
      : "";
  const [draft, setDraft] = useState(routeQuery);
  const tooLong = countCodePoints(draft) > MAX_QUERY_CODE_POINTS;

  useEffect(() => {
    const focusSearch = (event: KeyboardEvent) => {
      if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey) {
        return;
      }
      const target = event.target;
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
        return;
      }
      event.preventDefault();
      inputRef.current?.focus();
    };
    window.addEventListener("keydown", focusSearch);
    return () => window.removeEventListener("keydown", focusSearch);
  }, []);

  return (
    <form
      className="header-search"
      onSubmit={(event) => {
        event.preventDefault();
        void navigate(
          gameSearchPath({ query: draft.trim(), page: 1, pageSize: DEFAULT_PAGE_SIZE }),
        );
      }}
      role="search"
    >
      <label className="sr-only" htmlFor={inputId}>
        Buscar en el catálogo
      </label>
      <span className="search-icon" aria-hidden="true" />
      <input
        aria-describedby={tooLong ? errorId : undefined}
        aria-invalid={tooLong || undefined}
        autoComplete="off"
        id={inputId}
        name="q"
        onChange={(event) => setDraft(event.target.value)}
        placeholder="Buscar juegos por título…"
        ref={inputRef}
        type="search"
        value={draft}
      />
      <kbd aria-hidden="true">/</kbd>
      <button className="sr-only" type="submit">
        Buscar
      </button>
      {tooLong ? (
        <span className="header-search-error" id={errorId} role="alert">
          Usa como máximo 100 caracteres.
        </span>
      ) : null}
    </form>
  );
}
