# Post-launch Keycloak account journey review

**Evidence dates** 2026-11-12 and 2026-11-13. [Issue #472](https://github.com/rubhern/videogame-platform/issues/472)
follows the [8 November public opening](../public-production-release-acceptance.md).
The anonymous observations come from Chromium sessions on
[Gameómetro](https://gameometro.com/), with Spanish selected and viewports of
1320, 834, 390 and 320 CSS pixels. The proposed changes are in the working tree.

| Screen and reproduction | Observed | Expected and outcome |
|---|---|---|
| Catalogue → **Iniciar sesión** | Keycloak shows **Bienvenido, Jugón**, **Email**, **Contraseña**, recovery and a link to create an account. The brand is static. | Keep the entry points and make the lockup a home link across themed screens. |
| **Crear cuenta** | **Antes de crear tu cuenta** displays the full notice between the email and the acknowledgement. The notice text alone takes 585 px at 1320 px and 990 px at 320 px. | An initially collapsed, keyboard-operable disclosure puts the acknowledgement and submit action close to the email. |
| **Crear cuenta** → submit with both fields blank | The form marks both controls invalid and shows the Spanish errors, but neither field references its error. | Associate each error with its field so that it can be announced when the user returns to that field. |
| **Crear cuenta** → legal links | Both links target the registration tab. | Both links stay visible and open in another tab, so the original form keeps its state. |
| **Crear cuenta** → **Volver a iniciar sesión** → **Volver a Gameómetro** | The first link returns to Keycloak sign-in. The second returns to the application home. | Both destinations are correct. The lockup offers the same home destination. |
| Sign-in → **¿Has olvidado tu contraseña?** | **Recupera tu acceso** shows an email field and a link back to sign-in. | The anonymous recovery entry is reachable. See the controlled-account checks below. |

## Controlled-account checks

On 2026-11-13, one account on the live service used a dedicated email alias
and no ratings or profile data. The review covered the following transitions.

| Screen and reproduction | Observed | Expected and outcome |
|---|---|---|
| Submit a valid registration and open the verification email | **Verifica tu email** explains why verification is needed. The email arrives and its link opens **Elige tu contraseña** with first-time setup instructions. | Registration verifies the email before password setup. Passed. |
| Set the first password and choose **Ahora no** on **¿A qué juegas?** | The browser returns to the catalogue with an authenticated session. | First-time setup hands off to the product, and platform preferences can be skipped. Passed. |
| Sign in with an incorrect password | **Email o contraseña incorrectos.** appears on the sign-in page. The recovery link remains available. | Explain the failure and offer recovery. Passed. |
| Request a reset from **Recupera tu acceso** | The sign-in page shows a generic confirmation. The reset email arrives, and its link opens **Elige tu contraseña** with instructions specific to recovery. | Confirm the request without disclosing whether an account exists. Passed. |
| Set a new password | The browser returns to the catalogue with an authenticated session. | Recovery returns the user to the product. Passed. |
| Choose **Mi cuenta → Cerrar sesión** after setup and after recovery | Both attempts return to the catalogue with an anonymous session. | End the authenticated session and return home. Passed. |
| Reopen the used reset link | **Lo sentimos...** explains that the link expired or was used and tells the user to sign in or request a new one. **Volver a la aplicación** and **Volver a Gameómetro** both point home. | Reject reuse and provide a way back. Passed. The two equivalent return links are a minor redundancy observed on the live theme. |

## Registration eligibility decision

The owner subsequently approved a minimum age of 14 in the
[conditions of use](../../legal/public-texts-es.md#condiciones-de-uso).
The checkbox now requests acceptance of the conditions. Keycloak still
requires that acceptance before it creates the account.

## Post-deployment checks

Repeat the registration checks after deployment and confirm focus and disclosure
announcements with a screen reader. The disposable identity environment covers
the registration disclosure, new-tab navigation, and field error descriptions.
