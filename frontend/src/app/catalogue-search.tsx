import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import {
  countCodePoints,
  DEFAULT_PAGE_SIZE,
  gameSearchPath,
  MAX_QUERY_CODE_POINTS,
  readGameSearchParams,
} from "../features/search/game-search-params";

type SearchFormProps = {
  draft: string;
  inputRef: RefObject<HTMLInputElement | null>;
  onChange: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  tooLong: boolean;
  variant: "desktop" | "mobile";
};

function SearchForm({ draft, inputRef, onChange, onSubmit, tooLong, variant }: SearchFormProps) {
  const id = useId();
  const errorId = `${id}-error`;

  return (
    <form className={`header-search header-search-${variant}`} onSubmit={onSubmit} role="search">
      <label className="sr-only" htmlFor={id}>
        Buscar en el catálogo
      </label>
      <span className="search-icon" aria-hidden="true" />
      <input
        aria-describedby={tooLong ? errorId : undefined}
        aria-invalid={tooLong || undefined}
        autoComplete="off"
        id={id}
        name="q"
        onChange={(event) => onChange(event.target.value)}
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

  const formProps = { draft, onChange: setDraft, onSubmit: submit, tooLong };

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
