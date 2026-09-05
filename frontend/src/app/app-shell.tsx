import { useEffect, useRef } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";

const sections = [
  { to: "/", label: "Lanzamientos" },
  { to: "/search", label: "Buscar" },
] as const;

const sectionLinkClass =
  "inline-flex min-h-11 items-center text-sm font-semibold text-slate-200 hover:text-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200 aria-[current=page]:text-cyan-200";

export function AppShell() {
  const mainContentRef = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  const previousPathname = useRef(pathname);

  useEffect(() => {
    if (previousPathname.current === pathname) {
      return;
    }

    previousPathname.current = pathname;
    mainContentRef.current?.focus();
  }, [pathname]);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-950/95">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4 sm:px-8">
          <span className="text-sm font-semibold tracking-wide text-cyan-300">
            VideoGame Platform
          </span>
          <nav aria-label="Secciones principales">
            <ul className="flex flex-wrap gap-x-5 gap-y-2">
              {sections.map(({ to, label }) => (
                <li key={to}>
                  <NavLink
                    className={({ isActive }) =>
                      isActive ? `${sectionLinkClass} text-cyan-200` : sectionLinkClass
                    }
                    end
                    to={to}
                  >
                    {label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
        </div>
      </header>
      <main ref={mainContentRef} tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  );
}
