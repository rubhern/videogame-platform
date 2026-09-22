export type SelectIconName =
  | "platform"
  | "region"
  | "worldwide"
  | "japan"
  | "europe"
  | "north-america"
  | "region-area"
  | "region-unknown"
  | "playstation"
  | "nintendo-switch"
  | "nintendo-switch-2"
  | "android"
  | "ios"
  | "linux"
  | "macos"
  | "meta-quest"
  | "playstation-4"
  | "playstation-5"
  | "playstation-vr2"
  | "windows"
  | "xbox"
  | "xbox-one"
  | "xbox-series-x-s"
  | "steamvr"
  | "clock"
  | "title"
  | "rating"
  | "ascending"
  | "descending"
  | "page-size";

const maskedAssetIcons: readonly SelectIconName[] = [
  "playstation", "nintendo-switch", "nintendo-switch-2", "android", "ios", "linux", "macos", "meta-quest",
  "playstation-4", "playstation-5", "playstation-vr2", "windows", "xbox", "xbox-one", "xbox-series-x-s", "steamvr",
  "japan",
];

export function SelectIcon({ name }: { name: SelectIconName }) {
  if (maskedAssetIcons.includes(name)) {
    return <span aria-hidden="true" className={`app-select-icon app-select-icon-${name}`} />;
  }

  return (
    <svg aria-hidden="true" className="app-select-icon" fill="none" viewBox="0 0 20 20">
      {name === "platform" ? (
        <>
          <path d="M5.5 6.5h9a3 3 0 0 1 2.9 2.2l1 4.1a2 2 0 0 1-3.2 2l-2.1-1.6H6.9l-2.1 1.6a2 2 0 0 1-3.2-2l1-4.1a3 3 0 0 1 2.9-2.2Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
          <path d="M6 8.5v3M4.5 10h3M13.5 9.5h.01M15.5 11h.01" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
        </>
      ) : name === "region" || name === "worldwide" ? (
        <>
          <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeWidth="1.5" />
          <path d="M2.5 10h15M10 2.5c2 2 3 4.5 3 7.5s-1 5.5-3 7.5c-2-2-3-4.5-3-7.5s1-5.5 3-7.5Z" stroke="currentColor" strokeWidth="1.5" />
        </>
      ) : name === "region-area" ? (
        <>
          <path d="M10 17.5c3.5-3.4 5.5-6.3 5.5-9a5.5 5.5 0 0 0-11 0c0 2.7 2 5.6 5.5 9Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
          <circle cx="10" cy="8.5" r="2" stroke="currentColor" strokeWidth="1.5" />
        </>
      ) : name === "region-unknown" ? (
        <>
          <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeDasharray="2.5 2.5" strokeWidth="1.5" />
          <path d="M8.4 8.2a1.7 1.7 0 1 1 2.3 1.6c-.5.2-.9.6-.9 1.2v.3" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
          <path d="M9.8 13.6h.01" stroke="currentColor" strokeLinecap="round" strokeWidth="1.6" />
        </>
      ) : name === "clock" ? (
        <>
          <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeWidth="1.5" />
          <path d="M10 5.5V10l3 2" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
        </>
      ) : name === "title" ? (
        <path d="M4 5h12M4 10h9M4 15h12" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
      ) : name === "rating" ? (
        <path d="m10 2.5 2.3 4.65 5.13.75-3.71 3.62.88 5.1L10 14.2l-4.6 2.42.88-5.1L2.57 7.9l5.13-.75L10 2.5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
      ) : name === "page-size" ? (
        <>
          <rect x="3" y="3" width="14" height="14" rx="2" stroke="currentColor" strokeWidth="1.5" />
          <path d="M7 7h6M7 10h6M7 13h4" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
        </>
      ) : (
        <path d={name === "ascending" ? "M10 16V4m0 0-4 4m4-4 4 4" : "M10 4v12m0 0-4-4m4 4 4-4"} stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
      )}
    </svg>
  );
}
