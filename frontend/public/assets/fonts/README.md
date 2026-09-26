# Locally hosted reference fonts

Unmodified Latin and Latin Extended WOFF2 subsets of Mona Sans (variable width and
weight), Instrument Serif (regular and italic) and IBM Plex Mono are distributed with
the application under the SIL Open Font License. This keeps typography available
without runtime requests to a font service.

The files were obtained from the Google Fonts distribution (`fonts.gstatic.com`).
Upstream projects and the accompanying licence copies:

- [Mona Sans](https://github.com/github/mona-sans): [OFL](monasans-OFL.txt)
- [Instrument Serif](https://github.com/google/fonts/tree/main/ofl/instrumentserif): [OFL](instrumentserif-OFL.txt)
- [IBM Plex Mono](https://github.com/google/fonts/tree/main/ofl/ibmplexmono): [OFL](ibmplexmono-OFL.txt)

Keep the licence files with redistributed fonts. The
[font declarations](../../../src/styles/fonts.css) own subset ranges, weights and
fallback loading; [design guidelines](../../../../docs/development/frontend-design.md)
own usage rules.
