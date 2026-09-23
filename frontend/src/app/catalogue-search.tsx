import {
  useEffect,
  useId,
  useRef,
  useState,
  type FocusEvent,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
} from "react";
import { useLocation, useNavigate } from "react-router-dom";

import {
  countCodePoints,
  DEFAULT_PAGE_SIZE,
  gameSearchPath,
  MAX_QUERY_CODE_POINTS,
  readGameSearchParams,
} from "../features/search/game-search-params";
import {
  isSuggestionTerm,
  SUGGESTION_DEBOUNCE_MS,
  SUGGESTION_LIMIT,
} from "../features/search/game-suggestions";
import { GameSuggestionsPopup, type SuggestionsView } from "../features/search/game-suggestions-popup";
import { useGameSuggestionsQuery } from "../features/search/use-game-suggestions-query";
import { useDebouncedValue } from "../shared/browser/use-debounced-value";

type SearchFormProps = {
  draft: string;
  inputRef: RefObject<HTMLInputElement | null>;
  onChange: (value: string) => void;
  onOpenGame: (path: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  tooLong: boolean;
  variant: "desktop" | "mobile";
};

function SearchForm({ draft, inputRef, onChange, onOpenGame, onSubmit, tooLong, variant }: SearchFormProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const listboxId = `${id}-suggestions`;
  const optionId = (index: number) => `${id}-suggestion-${index}`;
  const formRef = useRef<HTMLFormElement>(null);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  // Each form owns its popup: only the variant the visitor is typing in opens and requests.
  const term = draft.trim();
  const debouncedTerm = useDebouncedValue(term, SUGGESTION_DEBOUNCE_MS);
  const expanded = open && isSuggestionTerm(term);
  const suggestions = useGameSuggestionsQuery(debouncedTerm, expanded);

  // Only a settled response for exactly the current text may render as suggestions; anything
  // older (a pending debounce, a superseded request) is shown as loading instead.
  const view: SuggestionsView =
    debouncedTerm !== term || suggestions.isPlaceholderData || suggestions.data === undefined
      ? suggestions.isError && debouncedTerm === term
        ? { kind: "error" }
        : {
            kind: "loading",
            rows: Math.min(Math.max(suggestions.data?.suggestions.length ?? 3, 1), SUGGESTION_LIMIT),
          }
      : suggestions.isError
        ? { kind: "error" }
        : { kind: "results", ...suggestions.data };
  const options = expanded && view.kind === "results" ? view.suggestions : [];
  const activeSuggestion = options[activeIndex];

  function close() {
    setOpen(false);
    setActiveIndex(-1);
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (!isSuggestionTerm(term)) return;
      event.preventDefault();
      if (!open) {
        setOpen(true);
        return;
      }
      if (options.length === 0) return;
      const last = options.length - 1;
      setActiveIndex((current) =>
        event.key === "ArrowDown"
          ? current >= last ? 0 : current + 1
          : current <= 0 ? last : current - 1,
      );
    } else if (event.key === "Enter" && activeSuggestion !== undefined) {
      event.preventDefault();
      close();
      onOpenGame(activeSuggestion.path);
    } else if (event.key === "Escape" && expanded) {
      // Closes only the popup: the text, the focus and an enclosing dialog stay as they are.
      event.preventDefault();
      event.stopPropagation();
      close();
    }
  }

  function handleBlur(event: FocusEvent<HTMLFormElement>) {
    if (!event.currentTarget.contains(event.relatedTarget)) close();
  }

  return (
    <form
      className={`header-search header-search-${variant}`}
      onBlur={handleBlur}
      onSubmit={(event) => {
        close();
        onSubmit(event);
      }}
      ref={formRef}
      role="search"
    >
      <label className="sr-only" htmlFor={id}>
        Buscar en el catálogo
      </label>
      <span className="search-icon" aria-hidden="true" />
      <input
        aria-activedescendant={activeSuggestion === undefined ? undefined : optionId(activeIndex)}
        aria-autocomplete="list"
        aria-controls={options.length > 0 ? listboxId : undefined}
        aria-describedby={tooLong ? errorId : undefined}
        aria-expanded={expanded}
        aria-invalid={tooLong || undefined}
        autoComplete="off"
        id={id}
        name="q"
        onChange={(event) => {
          setOpen(true);
          setActiveIndex(-1);
          onChange(event.target.value);
        }}
        onKeyDown={handleKeyDown}
        placeholder="Buscar juegos por título…"
        ref={inputRef}
        role="combobox"
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
      {expanded ? (
        <GameSuggestionsPopup
          activeIndex={activeIndex}
          listboxId={listboxId}
          onHover={setActiveIndex}
          onSearchAll={() => formRef.current?.requestSubmit()}
          onSelect={(suggestion) => {
            close();
            onOpenGame(suggestion.path);
          }}
          optionId={optionId}
          term={term}
          view={view}
        />
      ) : null}
    </form>
  );
}

export function CatalogueSearch() {
  const location = useLocation();
  const navigate = useNavigate();
  const desktopInputRef = useRef<HTMLInputElement>(null);
  const mobileInputRef = useRef<HTMLInputElement>(null);
  const mobileTriggerRef = useRef<HTMLButtonElement>(null);
  const mobileDialogRef = useRef<HTMLDialogElement>(null);
  const submittedRef = useRef(false);
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
      if (window.matchMedia?.("(max-width: 619px)").matches) {
        if (!mobileDialogRef.current?.open) mobileDialogRef.current?.showModal?.();
        mobileInputRef.current?.focus();
      } else {
        desktopInputRef.current?.focus();
      }
    };
    window.addEventListener("keydown", focusSearch);
    return () => window.removeEventListener("keydown", focusSearch);
  }, []);

  useEffect(() => {
    const phone = window.matchMedia?.("(max-width: 619px)");
    if (!phone) return;
    const closeOnResize = () => {
      if (!phone.matches) mobileDialogRef.current?.close?.();
    };
    phone.addEventListener("change", closeOnResize);
    return () => phone.removeEventListener("change", closeOnResize);
  }, []);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    submittedRef.current = true;
    mobileDialogRef.current?.close?.();
    void navigate(gameSearchPath({ query: draft.trim(), page: 1, pageSize: DEFAULT_PAGE_SIZE }));
  }

  function openGame(path: string) {
    submittedRef.current = true;
    mobileDialogRef.current?.close?.();
    void navigate(path);
  }

  const formProps = { draft, onChange: setDraft, onOpenGame: openGame, onSubmit: submit, tooLong };

  return (
    <div className="catalogue-search-control">
      <SearchForm {...formProps} inputRef={desktopInputRef} variant="desktop" />
      <button
        aria-haspopup="dialog"
        aria-label="Buscar juegos"
        className="mobile-search-trigger"
        onClick={() => {
          submittedRef.current = false;
          if (!mobileDialogRef.current?.open) mobileDialogRef.current?.showModal?.();
          mobileInputRef.current?.focus();
        }}
        ref={mobileTriggerRef}
        type="button"
      >
        <span aria-hidden="true" className="search-icon" />
      </button>
      <dialog
        aria-label="Buscar juegos"
        className="mobile-search-dialog"
        onClose={() => {
          if (!submittedRef.current) {
            if (window.matchMedia?.("(max-width: 619px)").matches) {
              mobileTriggerRef.current?.focus();
            } else {
              desktopInputRef.current?.focus();
            }
          }
          submittedRef.current = false;
        }}
        ref={mobileDialogRef}
      >
        <div className="mobile-search-dialog-content">
          <SearchForm {...formProps} inputRef={mobileInputRef} variant="mobile" />
          <button
            aria-label="Cerrar búsqueda"
            className="mobile-search-close"
            onClick={() => mobileDialogRef.current?.close?.()}
            type="button"
          >
            ×
          </button>
        </div>
      </dialog>
    </div>
  );
}
