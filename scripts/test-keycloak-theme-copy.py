#!/usr/bin/env python3
"""Check the approved product copy consumed by the inherited Keycloak footer."""

import pathlib
import unittest


class KeycloakThemeCopyTest(unittest.TestCase):
    def test_footer_uses_the_approved_spanish_product_messages(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        login = root / "docker/keycloak/themes/gameometro/login"
        messages = dict(
            line.split("=", 1)
            for line in (login / "messages/messages_es.properties").read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")
        )
        footer = (login / "footer.ftl").read_text(encoding="utf-8")
        for key, expected in {
            "gmLede": "Busca, puntúa, analiza. La referencia la marcas tú",
            "gmScaleTitle": "Cada puntuación, una temperatura",
        }.items():
            with self.subTest(key=key):
                self.assertEqual(messages[key], expected)
                self.assertIn(f'msg("{key}")', footer)


if __name__ == "__main__":
    unittest.main()
