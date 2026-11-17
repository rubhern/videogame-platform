#!/usr/bin/env python3
"""Check the hosted Gameómetro theme: its approved copy and the brand identity's static limits."""

import importlib.machinery
import importlib.util
import json
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

    def test_the_password_step_offers_no_sign_out_of_other_devices(self):
        # Owner decision (#474): style-only, so the inherited template and its unchecked default stay.
        self.assertIn("#kc-passwd-update-form .pf-v5-c-form__group:has(> #kc-form-options) {\n  display: none;", self.css)

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

    def test_the_shared_password_step_separates_activation_from_recovery(self):
        # Keycloak renders one password template for both journeys and, natively, picks
        # updatePasswordMessage while the account holds UPDATE_PASSWORD (first-time activation)
        # and resetPasswordMessage otherwise (recovery). Everything shared must fit both (#474).
        bundle = messages()
        for shared in ("updatePasswordTitle", "passwordNew", "accountUpdatedTitle", "accountUpdatedMessage"):
            with self.subTest(key=shared):
                self.assertNotRegex(bundle[shared].lower(), r"nueva|recupera|actualiza")
        self.assertIn("activar tu cuenta", bundle["updatePasswordMessage"])
        self.assertNotIn("nueva", bundle["updatePasswordMessage"])
        self.assertIn("nueva contraseña", bundle["resetPasswordMessage"])
        self.assertNotIn("activar", bundle["resetPasswordMessage"])


class SocialSignInThemeTest(unittest.TestCase):
    """#548: Google and Microsoft presentation, linking and terms copy (ADR-0045)."""

    def test_every_message_the_overridden_templates_use_is_defined(self):
        bundle = messages()
        for template in ("social-providers.ftl", "login-idp-link-confirm.ftl", "terms.ftl"):
            source = (LOGIN / template).read_text(encoding="utf-8")
            keys = set(re.findall(r'msg\("([^"]+)"', source)) | set(re.findall(r'label="([^"]+)"', source))
            for key in keys - {"termsTitle", "termsText"}:  # realm localization owns the approved notice
                with self.subTest(template=template, key=key):
                    self.assertIn(key, bundle)

    def test_buttons_name_the_provider_and_keep_its_untinted_mark(self):
        bundle = messages()
        self.assertEqual(bundle["gmSocialContinue"], "Continuar con {0}")
        self.assertEqual(bundle["identity-provider-login-label"], "O continúa con")
        source = (LOGIN / "social-providers.ftl").read_text(encoding="utf-8")
        self.assertNotIn("aria-label", source, "the visible name is the accessible name")
        self.assertEqual(source.count('aria-hidden="true"'), 2)
        css = (LOGIN / "resources/css/gameometro.css").read_text(encoding="utf-8")
        self.assertIn("#kc-social-providers svg.gm-social-mark {", css)
        self.assertIn("filter: none;", css.split("#kc-social-providers svg.gm-social-mark {", 1)[1].split("}", 1)[0])

    def test_registration_offers_the_providers_and_otherwise_matches_the_parent(self):
        source = (LOGIN / "register.ftl").read_text(encoding="utf-8")
        self.assertIn('<#elseif section = "socialProviders">', source)
        self.assertIn("<@registerCommons.termsAcceptance/>", source)

    def test_linking_puts_the_existing_account_first_and_terms_have_clear_actions(self):
        link = (LOGIN / "login-idp-link-confirm.ftl").read_text(encoding="utf-8")
        self.assertLess(link.index('id="linkAccount"'), link.index('id="updateProfile"'))
        self.assertIn("kcButtonPrimaryClass", link.split('id="linkAccount"')[0].rsplit("<button", 1)[1])
        bundle = messages()
        self.assertEqual((bundle["gmTermsAccept"], bundle["gmTermsDecline"]), ("Acepto las condiciones", "No acepto"))
        self.assertIn("contraseña", bundle["federatedIdentityConfirmLinkMessage"])


class PasswordPolicyTest(unittest.TestCase):
    """#473: one approved policy, its shipped denylist and the copy that announces it."""

    def setUp(self):
        loader = importlib.machinery.SourceFileLoader("denylist", str(ROOT / "scripts/build-password-denylist.py"))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        self.denylist = importlib.util.module_from_spec(spec)
        loader.exec_module(self.denylist)

    def test_both_realms_apply_the_same_policy_naming_the_shipped_denylist(self):
        for realm in ("docker/keycloak/import/videogame-platform-realm.json",
                      "deploy/public-production/keycloak/import/gameometro-realm.json"):
            with self.subTest(realm=realm):
                policy = json.loads((ROOT / realm).read_text(encoding="utf-8"))["passwordPolicy"]
                self.assertEqual(policy, f"length({self.denylist.MINIMUM_LENGTH}) and maxLength({self.denylist.MAXIMUM_LENGTH})"
                                         f" and notEmail and notContainsUsername and passwordBlacklist({self.denylist.NAME})")
        self.assertIn(f"docker/keycloak/password-blacklists/{self.denylist.NAME} /opt/keycloak/data/password-blacklists/",
                      DOCKERFILE.read_text(encoding="utf-8"))
        self.assertIn(f"!/docker/keycloak/password-blacklists/{self.denylist.NAME}",
                      (ROOT / ".dockerignore").read_text(encoding="utf-8"))

    def test_the_committed_denylist_is_the_reviewed_output(self):
        self.assertEqual(self.denylist.check(self.denylist.OUTPUT.read_bytes()), [])

    def test_the_copy_announces_the_enforced_minimum(self):
        bundle = messages()
        requirement = f"Usa al menos {self.denylist.MINIMUM_LENGTH} caracteres y evita contraseñas habituales."
        for key in ("updatePasswordMessage", "resetPasswordMessage"):
            with self.subTest(key=key):
                self.assertTrue(bundle[key].endswith(requirement), bundle[key])
        self.assertIn("demasiado habitual", bundle["invalidPasswordBlacklistedMessage"])


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
