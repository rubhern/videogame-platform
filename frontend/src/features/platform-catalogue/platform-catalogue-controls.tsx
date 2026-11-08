import { useId, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";

import { AppSelect } from "../../shared/ui/app-select";
import {
  advancedFilterCount,
  yearRangeLabel,
  type CatalogueReleaseState,
  type PlatformCatalogueSearch,
} from "./platform-catalogue-search";

const RELEASE_STATES: readonly { state: CatalogueReleaseState; label: string }[] = [
  { state: "all", label: "Todos" },
  { state: "released", label: "Ya lanzados" },
  { state: "upcoming", label: "Próximos" },
];

/**
 * **Todos | Ya lanzados | Próximos**: one night control parted in three native links, so the
 * choice lives in the URL and in history. The API decides what has come out at its own date.
 */
export function ReleaseStateSwitch({
  state,
  pathFor,
}: {
  state: CatalogueReleaseState;
  pathFor: (state: CatalogueReleaseState) => string;
}) {
  return (
    <nav aria-label="Estado de lanzamiento" className="catalogue-states">
      <ul className="catalogue-state-list">
        {RELEASE_STATES.map((option) => (
          <li key={option.state}>
            <Link
              aria-current={option.state === state ? "true" : undefined}
              className="catalogue-state"
              preventScrollReset
              to={pathFor(option.state)}
            >
              {option.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}

const MAX_QUERY_CODE_POINTS = 100;
const SEARCHABLE = /[\p{L}\p{N}]/u;

/**
 * The platform's own title search, always open: it applies on Enter or the search button, never
 * while a word is half typed, and explains a query the API would reject instead of sending it.
 */
export function CatalogueSearchField({
  platformName,
  query,
  onSearch,
}: {
  platformName: string;
  query: string | null;
  onSearch: (query: string | null) => void;
}) {
  const id = useId();
  const [value, setValue] = useState(query ?? "");
  const [problem, setProblem] = useState<string | null>(null);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = value.trim();
    if (trimmed === "") {
      setProblem(null);
      onSearch(null);
    } else if (Array.from(trimmed).length > MAX_QUERY_CODE_POINTS) {
      setProblem("Escribe como máximo 100 caracteres");
    } else if (!SEARCHABLE.test(trimmed)) {
      setProblem("Escribe alguna letra o número del título");
    } else {
      setProblem(null);
      onSearch(trimmed);
    }
  }

  return (
    <form
      aria-label={`Buscar en ${platformName}`}
      className="platform-search"
      onSubmit={submit}
      role="search"
    >
      <label className="sr-only" htmlFor={`${id}-input`}>
        Buscar por título en {platformName}
      </label>
      <span aria-hidden="true" className="search-icon" />
      <input
        aria-describedby={problem === null ? undefined : `${id}-problem`}
        aria-invalid={problem === null ? undefined : true}
        autoComplete="off"
        className="platform-search-input"
        enterKeyHint="search"
        id={`${id}-input`}
        onChange={(event) => setValue(event.target.value)}
        placeholder={`Buscar en ${platformName}…`}
        type="search"
        value={value}
      />
      {query === null && value === "" ? null : (
        <button
          aria-label="Borrar la búsqueda"
          className="platform-search-clear"
          onClick={() => {
            setValue("");
            setProblem(null);
            if (query !== null) onSearch(null);
          }}
          type="button"
        >
          <span aria-hidden="true" />
        </button>
      )}
      <button className="sr-only" type="submit">
        Buscar
      </button>
      {problem === null ? null : (
        <p className="platform-search-problem" id={`${id}-problem`} role="alert">
          {problem}
        </p>
      )}
    </form>
  );
}

type Chip = { key: string; label: string; removeLabel: string; to: string };

/**
 * The active search and filters as removable chips under the controls, so a folded panel never
 * hides what narrows the games. **Quitar todo** keeps only the platform and the release state.
 */
export function ActiveFilterChips({
  search,
  genreNames,
  pathFor,
}: {
  search: PlatformCatalogueSearch;
  genreNames: ReadonlyMap<string, string>;
  pathFor: (change: Partial<PlatformCatalogueSearch>) => string;
}) {
  const chips: Chip[] = [];
  if (search.q !== null) {
    chips.push({
      key: "q",
      label: `«${search.q}»`,
      removeLabel: `Quitar la búsqueda «${search.q}»`,
      to: pathFor({ q: null }),
    });
  }
  const years = yearRangeLabel(search.fromYear, search.toYear);
  if (years !== null) {
    chips.push({
      key: "years",
      label: years,
      removeLabel: `Quitar el filtro de años ${years}`,
      to: pathFor({ fromYear: null, toYear: null }),
    });
  }
  for (const genreId of search.genreIds) {
    const name = genreNames.get(genreId) ?? genreId;
    chips.push({
      key: `genre-${genreId}`,
      label: name,
      removeLabel: `Quitar el género ${name}`,
      to: pathFor({ genreIds: search.genreIds.filter((id) => id !== genreId) }),
    });
  }
  if (chips.length === 0) return null;

  return (
    <div className="catalogue-chips">
      <ul aria-label="Filtros activos" className="catalogue-chip-list">
        {chips.map((chip) => (
          <li key={chip.key}>
            <Link
              aria-label={chip.removeLabel}
              className="catalogue-chip"
              preventScrollReset
              to={chip.to}
            >
              <span className="catalogue-chip-label">{chip.label}</span>
              <span aria-hidden="true" className="catalogue-chip-remove" />
            </Link>
          </li>
        ))}
      </ul>
      {chips.length > 1 ? (
        <Link
          className="catalogue-chips-reset"
          preventScrollReset
          to={pathFor({ q: null, fromYear: null, toYear: null, genreIds: [] })}
        >
          Quitar todo
        </Link>
      ) : null}
    </div>
  );
}

type FiltersPanelProps = {
  id: string;
  open: boolean;
  search: PlatformCatalogueSearch;
  /** The response's facets; null while none has been read. */
  genres: readonly { id: string; name: string }[] | null;
  years: { earliest: number; latest: number } | null;
  totalItems: number | null;
  onChange: (change: Partial<PlatformCatalogueSearch>) => void;
  onClose: () => void;
};

const ANY_YEAR = "";

function yearOptions(
  years: { earliest: number; latest: number },
  selected: number | null,
): { value: string; label: string }[] {
  const options = [{ value: ANY_YEAR, label: "Cualquiera" }];
  const latest = Math.max(years.latest, selected ?? years.latest);
  const earliest = Math.min(years.earliest, selected ?? years.earliest);
  for (let year = latest; year >= earliest; year -= 1) {
    options.push({ value: String(year), label: String(year) });
  }
  return options;
}

/**
 * The advanced filters, folded by default: the known years of the platform's games and the genres
 * the other choices leave, each applied as soon as it is chosen. A selected genre always stays
 * among the choices so it can be removed here too.
 */
export function FiltersPanel({
  id,
  open,
  search,
  genres,
  years,
  totalItems,
  onChange,
  onClose,
}: FiltersPanelProps) {
  const range = years ?? (search.fromYear !== null || search.toYear !== null
    ? {
        earliest: search.fromYear ?? search.toYear ?? 1,
        latest: search.toYear ?? search.fromYear ?? 1,
      }
    : null);
  const selected = new Set(search.genreIds);

  return (
    <section aria-label="Filtros avanzados" className="catalogue-filters" hidden={!open} id={id}>
      <div className="catalogue-filters-group">
        <h3 className="catalogue-filters-title">Año de lanzamiento</h3>
        {range === null ? (
          <p className="catalogue-filters-note">Ningún juego de esta selección tiene año conocido</p>
        ) : (
          <div className="catalogue-years">
            <AppSelect
              icon="clock"
              label="Desde"
              onChange={(value) => {
                const year = value === ANY_YEAR ? null : Number(value);
                onChange({
                  fromYear: year,
                  toYear:
                    year !== null && search.toYear !== null && search.toYear < year
                      ? year
                      : search.toYear,
                });
              }}
              options={yearOptions(range, search.fromYear)}
              value={search.fromYear === null ? ANY_YEAR : String(search.fromYear)}
            />
            <AppSelect
              icon="clock"
              label="Hasta"
              onChange={(value) => {
                const year = value === ANY_YEAR ? null : Number(value);
                onChange({
                  toYear: year,
                  fromYear:
                    year !== null && search.fromYear !== null && search.fromYear > year
                      ? year
                      : search.fromYear,
                });
              }}
              options={yearOptions(range, search.toYear)}
              value={search.toYear === null ? ANY_YEAR : String(search.toYear)}
            />
          </div>
        )}
      </div>
      <div className="catalogue-filters-group">
        <h3 className="catalogue-filters-title" id={`${id}-genres`}>Géneros</h3>
        {genres === null ? (
          <div aria-hidden="true" className="catalogue-genre-skeleton" />
        ) : genres.length === 0 ? (
          <p className="catalogue-filters-note">Sin géneros para esta selección</p>
        ) : (
          <ul aria-labelledby={`${id}-genres`} className="catalogue-genres">
            {genres.map((genre) => {
              const pressed = selected.has(genre.id);
              return (
                <li key={genre.id}>
                  <button
                    aria-pressed={pressed}
                    className="catalogue-genre"
                    onClick={() =>
                      onChange({
                        genreIds: pressed
                          ? search.genreIds.filter((genreId) => genreId !== genre.id)
                          : [...search.genreIds, genre.id],
                      })
                    }
                    type="button"
                  >
                    <span aria-hidden="true" className="catalogue-genre-check" />
                    {genre.name}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
      <div className="catalogue-filters-actions">
        {advancedFilterCount(search) > 0 ? (
          <button
            className="filter-reset"
            onClick={() => onChange({ fromYear: null, toYear: null, genreIds: [] })}
            type="button"
          >
            Quitar filtros avanzados
          </button>
        ) : null}
        <button className="button button-primary catalogue-filters-done" onClick={onClose} type="button">
          {totalItems === null
            ? "Ver juegos"
            : `Ver ${totalItems === 1 ? "1 juego" : `${totalItems} juegos`}`}
        </button>
      </div>
    </section>
  );
}
