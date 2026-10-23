import { expect, test } from "@playwright/test";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import AxeBuilder from "@axe-core/playwright";

const origin = process.env.KEYCLOAK_TEST_ORIGIN ?? "";
const mailpit = process.env.MAILPIT_API_ORIGIN ?? "";
const mode = process.env.EMAIL_REVIEW_MODE;
const publicOrigin = process.env.KEYCLOAK_TEST_PUBLIC_ORIGIN ?? origin;
const proposed = mode === "after" || mode === "after-public";
test.skip(
  origin !== "http://keycloak:8080" || mailpit !== "http://mailpit:8025" ||
    !["before", "after", "after-public"].includes(mode ?? "") || process.env.KEYCLOAK_TEST_ADMIN_USERNAME !== "email-review-admin",
  "Use scripts/review-keycloak-email.sh with its isolated synthetic realm and mail sink.",
);
test.use({ trace: "off", screenshot: "off", video: "off", ignoreHTTPSErrors: mode === "after-public" });

test("actual multipart account emails render safely and their native actions work", async ({ page, request, browser }) => {
  test.setTimeout(120_000);
  let stage = "authenticate synthetic administrator";
  try {
    const tokenResponse = await request.post(`${origin}/realms/master/protocol/openid-connect/token`, {
      form: { client_id: "admin-cli", grant_type: "password", username: process.env.KEYCLOAK_TEST_ADMIN_USERNAME ?? "",
        password: process.env.KEYCLOAK_TEST_ADMIN_PASSWORD ?? "" },
    });
    expect(tokenResponse.status()).toBe(200);
    const headers = { Authorization: `Bearer ${(await tokenResponse.json()).access_token as string}` };
    const realm = `${origin}/admin/realms/videogame-platform`;
    const configuration = await (await request.get(realm, { headers })).json();
    const actions = await (await request.get(`${realm}/authentication/required-actions`, { headers })).json();
    const evidence = `test-results/transactional-email/${mode}`;
    await mkdir(evidence, { recursive: true });
    await writeFile(`${evidence}/inventory.json`, JSON.stringify({
      source: "disposable import of docker/keycloak/import/videogame-platform-realm.json",
      registrationAllowed: configuration.registrationAllowed, verifyEmail: configuration.verifyEmail,
      resetPasswordAllowed: configuration.resetPasswordAllowed, emailTheme: configuration.emailTheme,
      eventsListeners: configuration.eventsListeners,
      requiredActions: actions.map((action: { alias: string; enabled: boolean; defaultAction: boolean }) => ({
        alias: action.alias, enabled: action.enabled, defaultAction: action.defaultAction,
      })),
    }, null, 2));
    const username = `email-review-${crypto.randomUUID()}`;
    const address = `${username}@example.test`;
    const password = `Synthetic-${crypto.randomUUID()}!`;
    const created = await request.post(`${realm}/users`, { headers, data: {
      username, email: address, firstName: "Synthetic", lastName: "Reviewer", enabled: true,
      emailVerified: false, attributes: { locale: ["es"] },
      credentials: [{ type: "password", value: password, temporary: false }],
    } });
    expect(created.status()).toBe(201);
    const userPath = created.headers().location;
    // SMTP test emails go to the signed-in administrator, who belongs to master.
    const admins = await (await request.get(`${origin}/admin/realms/master/users?username=email-review-admin&exact=true`, { headers })).json();
    expect((await request.put(`${origin}/admin/realms/master/users/${admins[0].id as string}`, {
      headers, data: { email: address, attributes: { locale: ["es"] } },
    })).status()).toBe(204);
    const auth = `${publicOrigin}/realms/videogame-platform/protocol/openid-connect/auth?client_id=videogame-platform-bff&redirect_uri=${encodeURIComponent("http://application:8080/login/oauth2/code/keycloak")}&response_type=code&scope=openid&code_challenge=${"a".repeat(43)}&code_challenge_method=S256`;
    const outcomes: Record<string, unknown> = {};
    for (const flow of ["verification", "recovery", "actions", "smtp-test"]) {
      stage = `${flow}: trigger native email`;
      await request.delete(`${mailpit}/api/v1/messages`);
      if (flow === "recovery") {
        await page.goto(auth);
        await page.getByRole("link", { name: "¿Has olvidado tu contraseña?", exact: true }).click();
        await page.getByLabel("Usuario", { exact: true }).fill(username);
        await page.getByRole("button", { name: "Continuar", exact: true }).click();
      } else {
        const response = flow === "smtp-test"
          ? await request.post(`${realm}/testSMTPConnection`, { headers, data: configuration.smtpServer })
          : await request.put(`${userPath}/${flow === "verification" ? "send-verify-email" : "execute-actions-email"}`, {
            headers, ...(flow === "actions" ? { data: ["UPDATE_PASSWORD", "VERIFY_EMAIL"] } : {}),
          });
        expect(response.status()).toBe(204);
      }
      let messageId = "";
      await expect.poll(async () => {
        const inbox = await (await request.get(`${mailpit}/api/v1/messages`)).json();
        messageId = (inbox.messages as { ID: string }[])[0]?.ID ?? "";
        return Boolean(messageId);
      }).toBe(true);
      const mail = await (await request.get(`${mailpit}/api/v1/message/${messageId}`)).json() as {
        Subject: string; HTML: string; Text: string; From: { Name: string; Address: string };
        ReplyTo: unknown; ReturnPath: string;
      };
      stage = `${flow}: inspect multipart content`;
      expect(mail.From.Name === "Gameómetro" && mail.From.Address === "no-reply@gameometro.invalid").toBe(true);
      expect(Boolean(mail.HTML) && Boolean(mail.Text)).toBe(true);
      expect(!/\{\d\}|\$\{/.test(mail.HTML + mail.Text)).toBe(true);
      expect(!mail.Text.includes(password) && !mail.HTML.includes(address)).toBe(true);
      const htmlLinks = [...mail.HTML.matchAll(/href="([^"]+)"/g)].map((m) => m[1].replaceAll("&amp;", "&"));
      expect(htmlLinks.length).toBe(flow === "smtp-test" ? 0 : proposed ? 2 : 1);
      expect(new Set(htmlLinks).size).toBe(flow === "smtp-test" ? 0 : 1);
      const link = htmlLinks[0] ?? "";
      if (link) {
        expect(new URL(link).origin === publicOrigin).toBe(true);
        expect(new URL(link).pathname === "/realms/videogame-platform/login-actions/action-token").toBe(true);
        expect(mail.Text.includes(link)).toBe(true);
      }
      if (proposed) {
        const subjects: Record<string, string> = {
          verification: "Verifica tu email de Gameómetro", recovery: "Crea una nueva contraseña de Gameómetro",
          actions: "Revisa tu cuenta de Gameómetro", "smtp-test": "Prueba de email de Gameómetro",
        };
        expect(mail.Subject).toBe(subjects[flow]);
        expect(mail.Text.replaceAll("\r\n", "\n").includes("\n\nHola:") && mail.Text.trim().endsWith("Gameómetro")).toBe(true);
        expect(!/horas minutos|minutos minutos|nadie podrá entrar/.test(mail.Text + mail.HTML)).toBe(true);
        expect(!/<script|<link\b|<svg|data:image|cid:/.test(mail.HTML)).toBe(true);
      }
      const logo = [...mail.HTML.matchAll(/<img[^>]+src="([^"]+)"/g)].map((match) => match[1]);
      let logoUrl = "";
      if (proposed) {
        expect(logo.length).toBe(1);
        logoUrl = logo[0];
        const asset = new URL(logoUrl);
        expect(asset.origin === publicOrigin && /^\/resources\/[^/]+\/email\/gameometro\/img\/gameometro-logo\.png$/.test(asset.pathname)).toBe(true);
        expect(!asset.search && !asset.hash).toBe(true);
        if (mode === "after-public") {
          expect(asset.protocol === "https:" && !/localhost|keycloak|127\.0\.0\.1/.test(mail.HTML)).toBe(true);
        }
        const imageResponse = await request.get(logoUrl);
        expect(imageResponse.status()).toBe(200);
        expect(imageResponse.headers()["content-type"].includes("image/png")).toBe(true);
        const digest = (data: Buffer) => createHash("sha256").update(data).digest("hex");
        expect(digest(await imageResponse.body())).toBe(digest(await readFile("../docker/keycloak/themes/gameometro/email/resources/img/gameometro-logo.png")));
      }
      // Only the validated static logo URL is retained. Authentication URLs are always redacted.
      const sanitize = (value: string) => {
        const protectedLogo = logoUrl ? value.replaceAll(logoUrl, "__SAFE_STATIC_LOGO__") : value;
        return protectedLogo.replace(/https?:\/\/[^\s<>"']+/g, "https://example.invalid/accion-redactada")
          .replaceAll("__SAFE_STATIC_LOGO__", logoUrl).replaceAll(address, "synthetic@example.test").replaceAll(username, "synthetic-reviewer");
      };
      const html = sanitize(mail.HTML);
      await writeFile(`${evidence}/${flow}.html`, html);
      await writeFile(`${evidence}/${flow}.txt`, sanitize(mail.Text));
      await writeFile(`${evidence}/${flow}.json`, JSON.stringify({
        subject: mail.Subject, from: mail.From, replyTo: mail.ReplyTo, returnPath: mail.ReturnPath,
        actionOriginMatches: !link || new URL(link).origin === publicOrigin,
        sameLinkInTextAndHtml: !link || mail.Text.includes(link),
        logoUrl, publicHttpsFixture: mode === "after-public",
        note: "Synthetic Mailpit capture; action URLs redacted before persistence; validated static logo URL retained.",
      }, null, 2));
      stage = `${flow}: mobile, image-free and accessibility checks`;
      const preview = await browser.newContext({ ignoreHTTPSErrors: mode === "after-public" });
      const rendered = await preview.newPage();
      await rendered.route("**/*", (route) => route.request().url() === logoUrl ? route.continue() : route.abort());
      const accessibility: Record<string, string[]> = {};
      for (const width of [320, 390, 834, 1320]) {
        await rendered.setViewportSize({ width, height: 900 });
        await rendered.setContent(html);
        if (proposed) {
          expect(await rendered.locator("html").getAttribute("lang")).toBe("es");
          expect(await rendered.getByRole("img", { name: "Gameómetro", exact: true }).evaluate((image) => (image as HTMLImageElement).naturalWidth)).toBe(548);
          expect(await rendered.locator('table:not([role="presentation"])').count()).toBe(0);
          expect(await rendered.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
          accessibility[width] = (await new AxeBuilder({ page: rendered }).analyze()).violations.map((v) => v.id);
          expect(accessibility[width]).toEqual([]);
        }
        if (proposed) {
          // Anchor labels differ intentionally; every explanatory paragraph is shared.
          for (const paragraph of await rendered.locator("h1, p").all()) {
            if (await paragraph.locator("a").count() === 0 && !(await paragraph.innerText()).includes("Si el botón no funciona")) {
              expect(sanitize(mail.Text).includes(await paragraph.innerText())).toBe(true);
            }
          }
        }
        await rendered.screenshot({ path: `${evidence}/${flow}-${width}.png`, fullPage: true });
      }
      await rendered.setViewportSize({ width: 390, height: 900 });
      await rendered.emulateMedia({ colorScheme: "dark" });
      await rendered.screenshot({ path: `${evidence}/${flow}-dark.png`, fullPage: true });
      await rendered.emulateMedia({ colorScheme: "light" });
      await rendered.unroute("**/*");
      await rendered.route("**/*", (route) => route.abort());
      await rendered.setContent(html);
      if (proposed) {
        expect(await rendered.getByText("Tu cuenta, en Gameómetro", { exact: true }).isVisible()).toBe(true);
        expect(await rendered.getByRole("img", { name: "Gameómetro", exact: true }).evaluate((image) => (image as HTMLImageElement).naturalWidth)).toBe(0);
        if (link) {
          expect(await rendered.getByRole("link").count()).toBe(2);
          expect((await rendered.getByRole("link").first().boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
        }
        expect((await new AxeBuilder({ page: rendered }).analyze()).violations.map((violation) => violation.id)).toEqual([]);
      }
      await rendered.screenshot({ path: `${evidence}/${flow}-images-blocked.png`, fullPage: true });
      await rendered.setContent(html.replaceAll(/style="[^"]*"|bgcolor="[^"]*"/g, ""));
      await rendered.screenshot({ path: `${evidence}/${flow}-without-styles.png`, fullPage: true });
      if (proposed) {
        expect(await rendered.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        expect((await new AxeBuilder({ page: rendered }).analyze()).violations.map((violation) => violation.id)).toEqual([]);
      }
      // Keep basic inline typography/colour/table styling; omit progressive layout enhancements.
      const limitedCss = html.replaceAll(/(?:max-width|border-radius|display|-webkit-text-size-adjust|mso-padding-alt):[^;"']*;?/g, "");
      await rendered.setContent(limitedCss);
      if (proposed) {
        expect(await rendered.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        expect((await new AxeBuilder({ page: rendered }).analyze()).violations.map((violation) => violation.id)).toEqual([]);
      }
      await rendered.screenshot({ path: `${evidence}/${flow}-limited-css.png`, fullPage: true });
      await preview.close();
      stage = `${flow}: follow original native action`;
      if (flow === "verification") {
        await page.goto(link);
        const confirm = page.getByRole("link", { name: /proceder|continuar/i });
        if (await confirm.count()) await confirm.first().click();
        expect((await (await request.get(userPath, { headers })).json()).emailVerified === true).toBe(true);
      } else if (flow === "recovery" || flow === "actions") {
        await page.goto(link);
        const confirm = page.getByRole("link", { name: /proceder|continuar/i });
        if (await confirm.count()) await confirm.first().click();
        const newPassword = `Updated-${crypto.randomUUID()}!`;
        await page.locator("#password-new").fill(newPassword);
        await page.locator("#password-confirm").fill(newPassword);
        // The recovery flow resumes the OIDC redirect to an isolated callback fixture.
        await page.getByRole("button", { name: /guardar|enviar|continuar/i }).click();
        await expect(page.locator("#password-new")).toHaveCount(0);
        // Confirm the new credential actually authenticates; do not infer success from a rendered page.
        const signInContext = await browser.newContext({ ignoreHTTPSErrors: mode === "after-public" });
        const signIn = await signInContext.newPage();
        await signIn.goto(auth);
        await signIn.getByLabel("Usuario", { exact: true }).fill(username);
        await signIn.locator("#password").fill(newPassword);
        await signIn.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
        stage = `${flow}: authenticate with changed password`;
        await signIn.waitForURL((url) => url.origin === "http://application:8080" &&
          url.pathname === "/login/oauth2/code/keycloak", { timeout: 10_000 });
        await signInContext.close();
        const replay = await browser.newPage({ ignoreHTTPSErrors: mode === "after-public" });
        await replay.goto(link);
        expect(await replay.locator("#password-new").count()).toBe(0);
        await replay.close();
      }
      outcomes[flow] = { captured: true, actionCompleted: link ? true : "not applicable", accessibility };
    }
    await writeFile(`${evidence}/results.json`, JSON.stringify(outcomes, null, 2));
    await request.delete(userPath, { headers });
    await request.delete(`${mailpit}/api/v1/messages`);
  } catch {
    // Clear every page before Playwright can persist an automatic failure context.
    for (const context of browser.contexts()) {
      for (const openPage of context.pages()) await openPage.goto("about:blank").catch(() => undefined);
    }
    // Playwright errors can include bearer URLs; keep diagnostics to the safe stage name.
    throw new Error(`Email review failed at: ${stage}. Sensitive browser/request details suppressed.`);
  }
});
