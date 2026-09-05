import { useId, useState } from "react";
import { useNavigate } from "react-router-dom";

import {
  countCodePoints,
  gameSearchPath,
  MAX_QUERY_CODE_POINTS,
  type GameSearchParams,
} from "./game-search-params";

type GameSearchFormProps = {
  params: GameSearchParams;
};

/**
 * Searching is navigation: submitting writes the query to the URL so the result page is
 * shareable and reachable through browser history. Typing does not query the catalogue.
 */
export function GameSearchForm({ params }: GameSearchFormProps) {
  const navigate = useNavigate();
  const inputId = useId();
  const hintId = useId();
  const [draft, setDraft] = useState(params.query);

  const tooLong = countCodePoints(draft) > MAX_QUERY_CODE_POINTS;

  return (
    <form
      className="mt-8"
      onSubmit={(event) => {
        event.preventDefault();
        void navigate(gameSearchPath(params, { query: draft.trim(), page: 1 }));
      }}
      role="search"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <label className="text-sm text-slate-300" htmlFor={inputId}>
            Buscar en el catálogo
          </label>
          <input
            aria-describedby={hintId}
            aria-invalid={tooLong || undefined}
            autoComplete="off"
            className="min-h-11 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
            id={inputId}
            name="q"
            onChange={(event) => setDraft(event.target.value)}
            type="search"
            value={draft}
          />
        </div>
        <button
          className="inline-flex min-h-11 items-center justify-center rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 hover:bg-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
          type="submit"
        >
          Buscar
        </button>
      </div>
      <p className="mt-2 text-sm text-slate-400" id={hintId}>
        {tooLong
          ? `Usa como máximo ${MAX_QUERY_CODE_POINTS} caracteres.`
          : `Busca por título o por un título alternativo aprobado, con un máximo de ${MAX_QUERY_CODE_POINTS} caracteres.`}
      </p>
    </form>
  );
}
