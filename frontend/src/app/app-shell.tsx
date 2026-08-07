import { Outlet } from "react-router-dom";

export function AppShell() {
  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-950/95">
        <div className="mx-auto flex max-w-5xl items-center px-5 py-4 sm:px-8">
          <span className="text-sm font-semibold tracking-wide text-cyan-300">
            VideoGame Platform
          </span>
        </div>
      </header>
      <Outlet />
    </div>
  );
}
