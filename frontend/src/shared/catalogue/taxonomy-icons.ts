import type { SelectIconName } from "../ui/select-icon";

/**
 * Platform and region icons are frontend presentation only. A seeded platform or region is
 * recognized by its stable product ID. One acquired from releases later has an identity created
 * per environment, so it is recognized by its catalogue label, the product presentation value the
 * API returns (CAT-008 for regions). Provider identifiers never reach the browser, and anything
 * unrecognized falls back to an accessible generic marker, so new taxonomy never breaks the UI
 * (#178). Worldwide is a globe; Japan, Europe, North America, Asia, Korea, New Zealand, Brazil and
 * Australia use owner-supplied marks; other concrete areas share a neutral location marker; and
 * the unconfirmed-region sentinel has its own fallback.
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
  "meta quest 2": "meta-quest",
  "meta quest 3": "meta-quest",
  "meta quest 3s": "meta-quest",
  "meta quest pro": "meta-quest",
  "oculus quest": "meta-quest",
  "oculus quest 2": "meta-quest",
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

const regionIconByLabel: Readonly<Record<string, SelectIconName>> = {
  mundial: "worldwide",
  europa: "europe",
  norteamérica: "north-america",
  japón: "japan",
  "sin región confirmada": "region-unknown",
  asia: "asia",
  corea: "korea",
  "nueva zelanda": "new-zealand",
  brasil: "brazil",
  australia: "australia",
};

export function regionIcon(regionId: string, regionName: string): SelectIconName {
  return regionIconById[regionId] ?? regionIconByLabel[regionName.toLocaleLowerCase()] ?? "region-area";
}
