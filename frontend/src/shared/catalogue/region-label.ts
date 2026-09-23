const spanishRegionLabels: Readonly<Record<string, string>> = {
  europe: "Europa",
  japan: "Japón",
  "north america": "Norteamérica",
  unknown: "Sin región confirmada",
  worldwide: "Mundial",
};

export function regionLabel(name: string): string {
  return spanishRegionLabels[name.trim().toLocaleLowerCase("en")] ?? name;
}
