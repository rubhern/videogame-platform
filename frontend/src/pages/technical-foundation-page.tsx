const technicalCapabilities = [
  "React 19.2",
  "TypeScript estricto",
  "Vite 8.1",
  "React Router",
  "TanStack Query",
  "Tailwind CSS 4",
] as const;

export function TechnicalFoundationPage() {
  return (
    <main className="mx-auto flex max-w-5xl px-5 py-16 sm:px-8 sm:py-24">
      <section aria-labelledby="foundation-title" className="max-w-3xl">
        <p className="mb-4 inline-flex rounded-full border border-emerald-400/40 bg-emerald-400/10 px-3 py-1 text-sm font-medium text-emerald-300">
          Base técnica preparada
        </p>
        <h1
          id="foundation-title"
          className="text-4xl font-bold tracking-tight text-white sm:text-6xl"
        >
          El frontend ya puede crecer por slices verticales.
        </h1>
        <p className="mt-6 max-w-2xl text-lg leading-8 text-slate-300">
          Este esqueleto valida el stack aprobado sin adelantar ninguna funcionalidad
          de producto. Las experiencias de catálogo, autenticación y puntuaciones se
          incorporarán únicamente en sus issues correspondientes.
        </p>

        <h2 className="mt-12 text-sm font-semibold uppercase tracking-widest text-slate-400">
          Capacidades verificadas
        </h2>
        <ul className="mt-4 flex flex-wrap gap-3" aria-label="Tecnologías disponibles">
          {technicalCapabilities.map((capability) => (
            <li
              key={capability}
              className="rounded-lg border border-slate-700 bg-slate-900 px-4 py-2 text-sm text-slate-200"
            >
              {capability}
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}
