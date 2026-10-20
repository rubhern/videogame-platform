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


if __name__ == "__main__":
    unittest.main()
