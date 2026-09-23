import type { SelectIconName } from "../ui/select-icon";

/**
 * Platform and region icons are frontend presentation only, selected by stable product taxonomy
 * label. Provider identifiers never reach the browser; the label is the product presentation
 * value returned by the API. A platform or region acquired dynamically (or any unknown label)
 * falls back to an accessible generic marker, so new taxonomy never breaks the filter UI (#178).
 * Worldwide is a globe, the owner-supplied Japan mark is explicit, concrete areas otherwise
 * share a neutral location marker, and the unconfirmed-region sentinel has its own fallback.
 */
const platformIconById: Readonly<Record<string, SelectIconName>> = {
  "10000000-0000-4000-8000-000000000001": "playstation-5",
  "10000000-0000-4000-8000-000000000002": "nintendo-switch-2",
  "10000000-0000-4000-8000-000000000003": "windows",
  "10000000-0000-4000-8000-000000000004": "xbox-series-x-s",
};

const platformIconByName: Readonly<Record<string, SelectIconName>> = {
  android: "android",
  ios: "ios",
  linux: "linux",
  mac: "macos",
  macos: "macos",
  "mac os": "macos",
  "meta quest": "meta-quest",
  "nintendo switch": "nintendo-switch",
  "nintendo switch 2": "nintendo-switch-2",
  "playstation 4": "playstation-4",
  "playstation 5": "playstation-5",
  "playstation vr2": "playstation-vr2",
  "windows pc": "windows",
  "xbox one": "xbox-one",
  "xbox series x|s": "xbox-series-x-s",
  "xbox series s|x": "xbox-series-x-s",
  steamvr: "steamvr",
  "steam vr": "steamvr",
};

const regionIconById: Readonly<Record<string, SelectIconName>> = {
  "20000000-0000-4000-8000-000000000001": "worldwide",
  "20000000-0000-4000-8000-000000000002": "europe",
  "20000000-0000-4000-8000-000000000004": "north-america",
  "20000000-0000-4000-8000-000000000005": "japan",
  "20000000-0000-4000-8000-000000000003": "region-unknown",
};

export function platformIcon(platformId: string, platformName: string): SelectIconName {
  return platformIconById[platformId] ?? platformIconByName[platformName.toLocaleLowerCase()] ?? "platform";
}

export function regionIcon(regionId: string): SelectIconName {
  return regionIconById[regionId] ?? "region-area";
}
