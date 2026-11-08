/** Presentation keys only. Product identity resolution remains in taxonomy-icons.ts (#178). */
export const platformLogoAssets = {
  "nintendo-switch": { file: "nintendo-switch.png", treatment: "legacy-mask", label: "Switch" },
  "nintendo-switch-2": { file: "nintendo-switch-2.png", treatment: "legacy-mask", label: "Switch 2" },
  playstation: { file: "playstation-5.png", treatment: "legacy-mask", label: "PlayStation" },
  "playstation-4": { file: "playstation-4.png", treatment: "legacy-mask", label: "PS4" },
  "playstation-5": { file: "playstation-5.png", treatment: "legacy-mask", label: "PS5" },
  "playstation-vr2": { file: "playstation-vr2.png", treatment: "legacy-mask", label: "PS VR2" },
  xbox: { file: "xbox-series-x-s.png", treatment: "legacy-mask", label: "Xbox" },
  "xbox-one": { file: "xbox-one.png", treatment: "legacy-mask", label: "Xbox One" },
  "xbox-series-x-s": { file: "xbox-series-x-s.png", treatment: "legacy-mask", label: "X|S" },
  windows: { file: "windows.svg", treatment: "original", label: "Windows" },
  macos: { file: "macos.png", treatment: "legacy-mask", label: "macOS" },
  ios: { file: "ios.png", treatment: "legacy-mask", label: "iOS" },
  android: { file: "android.svg", treatment: "original", label: "Android" },
  linux: { file: "linux.svg", treatment: "original", label: "Linux" },
  "meta-quest": { file: "meta-quest.png", treatment: "legacy-mask", label: "Meta Quest" },
  // The former Steam symbol was not SteamVR. Use its name until suitable artwork is cleared.
  steamvr: { file: null, treatment: "text", label: "SteamVR" },
} as const;

export type PlatformLogoName = keyof typeof platformLogoAssets;

export function isPlatformLogoName(name: string): name is PlatformLogoName {
  return Object.hasOwn(platformLogoAssets, name);
}
