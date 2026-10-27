# Platform asset register

This register owns bundled platform-artwork provenance, reuse notices and unresolved
permissions for #288. The [frontend design guidelines](../../../../docs/development/frontend-design.md#platform-identities-and-logos)
own presentation rules. Selection is implemented in
[`platform-logo-assets.ts`](../../../src/shared/catalogue/platform-logo-assets.ts);
product identity remains with the existing #178 taxonomy mapping. This is not a
manufacturer endorsement or a legal determination of referential trademark use.

## Status and release boundary

- **Approved source:** original artwork with explicit reuse terms, implemented with
  credit. This describes the documented copyright basis, not a blanket trademark
  licence, manufacturer approval of Gameómetro, or public-release acceptance.
- **Private-development selection:** sourced artwork selected for this personal,
  private, non-commercial application on the stated referential-use basis. Owner
  acceptance of the implementation remains separate from legal clearance and public
  distribution. This status never means a manufacturer granted a trademark licence.
- **Provisional:** an existing asset retained for current private operation; original
  provenance or permission is incomplete. Retention does not clear redistribution.
- **Blocked candidate:** identified alternative that must not enter deployable assets
  until its outstanding permission or identity problem is resolved.

The owner's 2026-10-27 decision preserves real brand identities. It does not grant
rights owned by manufacturers. Windows stays Windows, macOS stays Apple/macOS,
SteamVR is not Steam, and a storefront never becomes an operating-system platform.
There is no Steam taxonomy entry or new provider mapping in this change.

## Selected set

`legacy-mask` below means the existing monochrome rendering is retained, not that
recolouring new official artwork is permitted. Most retained files entered with
owner-supplied assets in `2d3909d`; Git history is not an original-source licence.
The compact and large treatments are delivered by #288 for owner acceptance; #287
consumes them without reopening this audit. Public-use risks remain with the existing
release gates, not an indefinitely open asset-selection issue.

| Platform / file | Status | Compact / large selection and remaining decision |
|---|---|---|
| Nintendo Switch 2 · `nintendo-switch-2.png` | Provisional | Keep symbol plus 2; same master at both sizes. Native 500×266 is sufficient for a 200px 2× slot. Original supplier/permission unverified; [Nintendo permission route](https://www.nintendo.com/en-gb/Support/Contact/Business-Proposals-Including-General-Requests-to-Use-Nintendo-Trademarks-or-Copyrights-or-Requests-for-Promotional-Items-or-Donations-2483154.html). IGDB's grey rectangle is rejected. |
| PlayStation 5 · `playstation-5.png` | Provisional | Keep correct PS5 wordmark. Native 360×108: large width capped at 180 CSS px to avoid 2× upscaling. Replace only after permission for the official light candidate below is established. Never PS5 Pro. |
| Xbox Series X\|S · `xbox-series-x-s.png` | Provisional | Keep combined X\|S master, native 1216×584. A Series X-only image is not an alternative. [Xbox fansite conditions](https://www.xbox.com/en-US/legal/fansiteguidelines) do not establish that a multi-platform discovery service qualifies. |
| Windows PC · `windows.svg` | Private-development selection; public-use review remains | Unmodified modern blue four-pane SVG from [Microsoft Learn](https://learn.microsoft.com/en-us/media/logos/logo_windows.svg), also used for the broad Windows categories on [Microsoft Q&A](https://learn.microsoft.com/en-us/answers/). Same vector for compact and large; visible Windows name in card lists, unchanged product ID and full platform name. No edition number, generic device or Windows 11-only claim. See the specific basis and restrictions below; no manufacturer licence claimed. Replaces the retired waving-flag PNG. |
| Nintendo Switch · `nintendo-switch.png` | Provisional | Keep controller symbol, native 512×512, at both sizes. No OLED/Lite substitution. Original supplier/permission unverified; same Nintendo permission route as Switch 2. |
| PlayStation 4 · `playstation-4.png` | Provisional | Keep original PS4 master, native 920×253. No PS4 Pro substitution. Original supplier/permission unverified; [SIE terms](https://sonyinteractive.com/en/terms-of-service/). |
| PlayStation VR2 · `playstation-vr2.png` | Provisional | Retain legacy headset silhouette plus full product name. Native 200×200: large width is capped at 100 CSS px for 2×. A cleared PS VR2 lockup is still required for prominent use. Do not add another generic device symbol. |
| Xbox One · `xbox-one.png` | Provisional | Keep Xbox symbol plus full product name, native 512×512. Original supplier/permission unresolved; the symbol alone is family-level, not generation-specific. |
| Android · `android.svg` | Approved source; trademark review remains | Original flat robot head from [Google's download](https://developer.android.com/static/images/brand/android-head_flat.svg), linked by its [brand guidelines](https://developer.android.com/distribute/marketing-tools/brand-guidelines). Same scalable original at both sizes, original green, no masking. Robot reproduction is allowed under CC BY 3.0 with the required credit, supplied in the shared footer. Google's broader brand-review conditions remain applicable; no approval of this service or its marketing is claimed. |
| Linux · `linux.svg` | Approved source | Original vector Tux from [the Linux source tree](https://github.com/torvalds/linux/blob/master/Documentation/images/logo.svg); same full-colour SVG at both sizes. [Reuse notice](https://github.com/torvalds/linux/blob/master/Documentation/images/COPYING-logo) explicitly permits use with credit to Larry Ewing, Garrett LeSage and IFo Hancroft. Credit is in the shared footer; notice retained as `COPYING-linux.txt`. [Linux word-mark obligations](https://www.linuxfoundation.org/legal/the-linux-mark) remain distinct. |
| macOS · `macos.png` | Provisional | Keep existing Apple/computer mark plus macOS name, native 512×512. No version-landscape icon and no generic replacement. Composite origin and graphic permission remain unverified under [Apple's guidelines](https://www.apple.com/legal/intellectual-property/guidelinesfor3rdparties.html). |
| iOS · `ios.png` | Provisional | Retain Apple silhouette plus full iOS name, native 512×512; it is not a distinct OS logo. Original source and graphic permission remain unresolved under the same Apple guidelines. |
| Meta Quest · `meta-quest.png` | Provisional | Retain existing infinity plus full product/device name, native 256×256; large width capped at 128 CSS px for 2×. Family-level, not a Quest 2-only identity. Seek a generic official Quest lockup under [Meta's conditions](https://developers.meta.com/vr/resources/publish-brand-guidelines/); exact artwork/permission not established. |
| SteamVR · plain `SteamVR` text | Final name-based selection for this increment; graphical candidate isolated | Explicit name at compact and large sizes. Official positive/white-inverse SteamVR lockups were verified in Valve's brand book and the original EPS pack was retrieved for local review. Its permission requirement and 50px minimum logo height are not satisfied by a 20px game-card mark. The name is the supported selection, not a pending implementation task. No fabricated logo or Steam storefront substitution. |

Generic internal `playstation` and `xbox` presentation aliases retain their previous
file resolution. They do not merge product IDs. A genuinely unknown platform retains
the generic controller **and its product-owned name**. Failed newly sourced images
retain their known brand name, rather than substituting a different brand/device.

## Windows: referential basis and restrictions

**Verified source:** the selected SVG is Microsoft's own four-pane, single-blue
artwork, not a traced icon or a four-colour corporate Microsoft logo. It contains
neither an edition name nor a Windows Start-button variant. The
[Windows brand guide](https://cdn-dynmedia-1.microsoft.com/is/content/microsoftcorp/microsoft/mscle/documents/presentations/Windows_Trademark_guidelines_2023.pdf)
distinguishes broad Windows branding from edition lockups, permits symbol contexts
with the Windows name nearby, and specifies contrast/spacing. Original blue is kept;
its contrast against Night is measured separately in the local visual evidence.
No manufacturer approval is implied.

**Intended use and interpretation:** Windows identifies the actual Windows PC
platform in factual catalogue/filter contexts, subordinate to Gameómetro, with its
name and a non-affiliation notice. This personal, non-commercial use is supported by
the own-use exception stated in [Microsoft Learn's terms](https://learn.microsoft.com/en-us/legal/termsofuse#personal-and-non-commercial-use-limitation).
The SVG is retained unmodified. This is the bounded private-development basis for
selection, not a statement that all website graphics have a general reuse licence.

**Legal distinction:** [EU trademark Regulation Article 14](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32017R1001)
and [Spanish Trademark Act Article 37](https://www.boe.es/buscar/act.php?id=BOE-A-2001-23093#a37)
provide for honest identifying/referential uses. They support considering factual
reference independently of a manufacturer's licence policy; they do not automatically
resolve copyright, contract applicability or whether a particular execution is honest.
No public-domain conclusion is made about the simple geometry.

**Material restrictions:** Microsoft's brand guide requests a trademark licence;
its general terms also reserve copying/retransmission rights for graphics. The Learn
personal-use exception and referential rationale are not a blanket override of those
restrictions. Public hosting, repository redistribution, prominent marketing use and
the applicability of any contractual terms remain explicit review items at the
[existing public-production purchase/exposure gates](../../../../docs/development/delivery-lifecycle.md#public-production-gates).
Public launch is not authorized by this working-tree implementation. No contract,
permission request or manufacturer's consent was invented. The asset selection itself
is ready for private-development owner acceptance.

## Official candidates not bundled

**PS5 priority candidate:** the [official PS5 product page](https://www.playstation.com/en-us/ps5/)
references [this white transparent original](https://gmedia.playstation.com/is/image/SIEPDC/ps5-badge-02-en-11jun20?fmt=png-alpha).
The native source is 400×102, visible alpha bounds 386×85; it can support roughly
200 CSS px at 2× without inventing pixels. It is the correct PS5, not Pro. No SVG or
higher native resolution was verified. Its source is verified; public redistribution
permission is not. The [SIE library](https://sonyinteractive.com/en/news/asset-library/)
was inspected, but its asset-list API returned 403; no access control was bypassed.
SIE's terms permit personal, non-commercial internal use and otherwise require
permission, with possible asset-specific terms. Keep this candidate only in ignored
local review evidence until permission for bundling/hosting is resolved. The earlier
800×174 IGDB black PS5 version is a reference, not a source-rights substitute.

**SteamVR:** [Valve's current brand book](https://shared.fastly.steamstatic.com/community_assets/images/steamworks_docs/english/steam_brandGuidelines.pdf)
distinguishes SteamVR and its feature designations. Page 30 supplies the positive
black and inverse white official lockups, with the SteamVR name and registered mark;
the inverse variant is the appropriate Night candidate. Page 31 gives a 50px minimum
onscreen height and clear space. Page 5 says the guidelines convey no trademark
rights and require a separate licence; page 30 describes permission for qualifying
software/content partners and hardware makers. Gameómetro has no such agreement.
These are material usage restrictions, not a claim that every factual reference to
SteamVR is prohibited by trademark law. The legal distinction above applies, but it
does not establish a basis for bundling this complex graphical artwork here. Its
[official EPS asset pack](https://shared.fastly.steamstatic.com/community_assets/images/steamworks_docs/english/steam_brandAssets.eps)
is a source route, not permission. The original EPS and a rendered, unmodified page-30
variant sheet are isolated in ignored local review evidence, outside deployable
frontend resources. Do not extract, redraw or recolour a symbol into an allegedly
approved compact mark. The explicit SteamVR name is the final integrated alternative
allowed by this issue; public legal review is not an implementation blocker.

**Other rejected IGDB defaults:** PS5 Pro, PS4 Pro, Switch OLED, Series X, Windows 11,
Android 13 and Quest 2 do not represent their broader product entries. Opaque plates,
thin black lettering, padded canvases and version-style Mac artwork also prevent a
coherent Night presentation. IGDB availability establishes neither official origin
nor third-party permission. No IGDB evaluation binary is bundled here.

## Provenance fingerprints and notices

The SVG originals were retrieved on 2026-10-27, unmodified. Their fingerprints:

- `android.svg`: `5f05dae657da9f31e5d4a09b7976f39881617f6b3e4d4562d9ee16295d250bdb`
- `linux.svg`: `a31038faddfc457ada7948ab18c5cd8bad68863f5520144d5cbed5acb84f7b33`
- `windows.svg`: `0f6b74d8d8ede1ec0e454b3c6fcdfba65fbe989af9a49ad4ff0c498f4dc3afbb`

Android robot credit is reproduced in the shared footer with the
[CC BY 3.0 licence](https://creativecommons.org/licenses/by/3.0/). Android is a trademark
of Google LLC. Tux remains credited to its three artists. No asset is represented as
Gameómetro-owned. The PNGs superseded by the two sourced SVGs, and the misidentified
Steam symbol formerly named `steamvr.png`, were removed. `COPYING-linux.txt` retains
the upstream notice text with trailing whitespace normalized for the CI check.

Before public exposure or distributing provisional artwork, resolve exact source,
hosting/redistribution permission, required notices, clear space/minimum size and any
removal obligations. The [existing public rights gates](../../../../docs/development/delivery-lifecycle.md#public-production-gates)
remain in force. No manufacturer was contacted and no purchase, account, endorsement,
licence agreement, public publication or final owner acceptance occurred in this change.
Those public-release risks do not require keeping #288 open after the owner accepts
this private-development implementation and its documented name-based SteamVR choice.

## Acceptance of this increment

Implementation is prepared for owner review: the modern Windows SVG is integrated in
the shared compact/large renderer, Windows is visibly named in card lists, and SteamVR
uses the explicit name-based alternative with its official graphical variants isolated.
Nintendo Switch 2, PS5 and combined Xbox Series X|S retain their established masters;
Android/Tux originals and attribution remain. Unknown-platform fallbacks and product
identity resolution are preserved. No #287 route, provider path or backend capability
is part of this change.

Owner acceptance of this private-development visual system is the remaining #288
decision. The source/usage risks documented here are inputs to the existing public
release gates, not requests for additional implementation or new follow-up issues.
