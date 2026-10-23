#!/usr/bin/env python3
"""Check the hosted Gameómetro theme: its approved copy and the brand identity's static limits."""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOGIN = ROOT / "docker/keycloak/themes/gameometro/login"
DOCKERFILE = ROOT / "deploy/private-dev/keycloak/Dockerfile"


def messages():
    return dict(
        line.split("=", 1)
        for line in (LOGIN / "messages/messages_es.properties").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    )


class KeycloakThemeTest(unittest.TestCase):
    def setUp(self):
        self.css = (LOGIN / "resources/css/gameometro.css").read_text(encoding="utf-8")
        self.footer = (LOGIN / "footer.ftl").read_text(encoding="utf-8")

    def test_footer_uses_the_approved_spanish_product_messages(self):
        bundle = messages()
        for key, expected in {
            "gmClaim": "Tu criterio.",
            "gmClaimEcho": "Tu historia.",
            "gmLede": "Busca, puntúa, analiza. La referencia la marcas tú",
            "gmScaleTitle": "Cada puntuación, una temperatura",
        }.items():
            with self.subTest(key=key):
                self.assertEqual(bundle[key], expected)
                self.assertIn(f'msg("{key}")', self.footer)

    def test_every_footer_message_is_defined(self):
        bundle = messages()
        for key in re.findall(r'msg\("([^"]+)"\)', self.footer):
            with self.subTest(key=key):
                self.assertIn(key, bundle)

    def test_the_stage_is_css_only_and_still(self):
        # The only image the theme loads is the canonical lockup; everything else is CSS.
        urls = set(re.findall(r"url\('([^']+)'\)", self.css))
        self.assertEqual(urls, {"../fonts/mona-sans-latin.woff2", "../img/gameometro-logo.svg"})
        self.assertNotIn("infinite", self.css)
        self.assertNotIn("background-attachment: fixed", self.css)

    def test_retired_visual_language_stays_out(self):
        for retired in ("backdrop-filter", "Instrument Serif", "IBM Plex Mono", "aurora", "night-hero"):
            with self.subTest(retired=retired):
                self.assertNotIn(retired, self.css)

    def test_the_image_copies_only_the_assets_the_theme_uses(self):
        dockerfile = DOCKERFILE.read_text(encoding="utf-8")
        copied = set(re.findall(r"frontend/\S+", dockerfile))
        self.assertEqual(
            copied,
            {
                "frontend/src/shared/brand/gameometro-logo.svg",
                "frontend/src/shared/brand/gameometro-mark.svg",
                "frontend/public/assets/fonts/mona-sans-latin.woff2",
                "frontend/public/assets/fonts/monasans-OFL.txt",
            },
        )
        for source in copied:
            with self.subTest(source=source):
                self.assertTrue((ROOT / source).is_file())


class KeycloakEmailThemeTest(unittest.TestCase):
    def test_html_and_text_keep_the_provider_argument_contract(self):
        # These positions are supplied by Keycloak's inherited per-flow templates.
        email = ROOT / "docker/keycloak/themes/gameometro/email"
        bundle = dict(line.split("=", 1) for line in
                      (email / "messages/messages_es.properties").read_text().splitlines()
                      if line and not line.startswith("#"))
        for flow, positions in {
            "passwordReset": {"0", "2", "3"},
            "emailVerification": {"0", "2", "3"},
            "executeActions": {"0", "2", "3", "4"},
            "emailTest": {"0"},
        }.items():
            with self.subTest(flow=flow):
                self.assertEqual(set(re.findall(r"\{(\d+)\}", bundle[flow + "Body"])), positions)
        # Narrow adapters pass native variables into a shared, HTML-escaped presentation macro.
        for flow in ("password-reset", "email-verification", "executeActions"):
            source = (email / "html" / (flow + ".ftl")).read_text()
            self.assertIn("link=link", source)
            self.assertIn("linkExpirationFormatter(linkExpiration)", source)
            self.assertNotIn("?no_esc", source)
        self.assertIn('href="${link}"', (email / "html/template.ftl").read_text())

    def test_logo_uses_the_native_email_resource_url_and_canonical_raster(self):
        email = ROOT / "docker/keycloak/themes/gameometro/email"
        source = (email / "html/template.ftl").read_text()
        self.assertIn('${url.resourcesUrl}/img/gameometro-logo.png', source)
        self.assertNotIn('http://', source)
        self.assertNotIn('https://', source)
        self.assertTrue((email / "resources/img/gameometro-logo.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n"))



if __name__ == "__main__":
    unittest.main()
