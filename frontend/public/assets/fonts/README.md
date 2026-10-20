# Locally hosted reference fonts

Unmodified Latin and Latin Extended WOFF2 subsets of Mona Sans (variable width and
weight) and IBM Plex Mono are distributed with the application under the SIL Open Font
License. This keeps typography available without runtime requests to a font service. The
application declares both; the hosted identity theme copies only the Mona Sans Latin subset.

The files were obtained from the Google Fonts distribution (`fonts.gstatic.com`).
Upstream projects and the accompanying licence copies:

- [Mona Sans](https://github.com/github/mona-sans): [OFL](monasans-OFL.txt)
- [IBM Plex Mono](https://github.com/google/fonts/tree/main/ofl/ibmplexmono): [OFL](ibmplexmono-OFL.txt)

Keep the licence files with redistributed fonts. The
[font declarations](../../../src/styles/fonts.css) and the
[hosted identity theme](../../../../docker/keycloak/themes/gameometro/login/resources/css/gameometro.css) own subset ranges, weights and
fallback loading; [design guidelines](../../../../docs/development/frontend-design.md)
own usage rules.
