// #374 negative feasibility evidence, not the Remember Me acceptance suite.
// Tokens stay in this Node process; output contains only fixed classifications.
const assert = require('node:assert/strict');
const { createHash, createPublicKey, randomBytes, verify } = require('node:crypto');
const { chromium } = require('playwright');

const origin = 'http://keycloak:8080';
const issuer = `${origin}/realms/remember-me-probe`;
const callback = `${origin}/probe-callback`;
const withMapper = process.env.PROBE_NATIVE_MAPPER === 'true';
const clientId = 'videogame-platform-bff';
const secret = randomBytes(32).toString('hex');
const password = randomBytes(24).toString('hex');
const noteNames = ['rememberMe', 'remember_me', 'REMEMBER_ME'];

async function json(url, options) {
  const response = await fetch(url, options);
  assert.ok(response.ok, `HTTP failure: ${response.status}`);
  const body = await response.text();
  return body ? JSON.parse(body) : undefined;
}

async function main() {
  const readyBy = performance.now() + 120_000;
  while (true) {
    try {
      await json(`${origin}/realms/master/.well-known/openid-configuration`);
      break;
    } catch {
      assert.ok(performance.now() < readyBy, 'Disposable Keycloak readiness timed out');
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  }
  const admin = await json(`${origin}/realms/master/protocol/openid-connect/token`, {
    method: 'POST', body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli',
      username: process.env.PROBE_ADMIN_USERNAME ?? 'probe-admin', password: process.env.PROBE_ADMIN_PASSWORD }),
  });
  const headers = { Authorization: `Bearer ${admin.access_token}`, 'Content-Type': 'application/json' };
  const server = await json(`${origin}/admin/serverinfo`, { headers });
  assert.equal(server.systemInfo.version, '26.7.5', 'Reassess source review when Keycloak changes');
  const mappers = server.protocolMapperTypes['openid-connect'].map(mapper => mapper.id);
  assert.ok(mappers.includes('oidc-usersessionmodel-note-mapper'));
  assert.equal(mappers.includes('gameometro-remember-me'), withMapper);

  await json(`${origin}/admin/realms`, { method: 'POST', headers, body: JSON.stringify({
    realm: 'remember-me-probe', enabled: true, sslRequired: 'none', rememberMe: true,
    ...(withMapper ? { loginTheme: 'gameometro' } : {}),
    ssoSessionIdleTimeout: 1800, ssoSessionMaxLifespan: 28800,
    ssoSessionIdleTimeoutRememberMe: 604800, ssoSessionMaxLifespanRememberMe: 2592000,
    users: [{ username: 'probe-user', enabled: true, email: 'probe@example.test', emailVerified: true,
      firstName: 'Synthetic', lastName: 'Probe', credentials: [{ type: 'password', value: password }] }],
    clients: [{ clientId, secret, enabled: true, protocol: 'openid-connect', publicClient: false,
      standardFlowEnabled: true, directAccessGrantsEnabled: false, redirectUris: [callback],
      attributes: { 'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': callback },
      protocolMappers: [
        ...(withMapper ? [{ name: 'native remember me', protocol: 'openid-connect', protocolMapper: 'gameometro-remember-me', config: {} }] : []),
        ...noteNames.map((note, index) => ({ name: `probe note ${index}`, protocol: 'openid-connect',
          protocolMapper: 'oidc-usersessionmodel-note-mapper', config: {
            'user.session.note': note, 'claim.name': `probe_note_${index}`, 'jsonType.label': 'boolean',
            'id.token.claim': 'true', 'access.token.claim': 'false', 'userinfo.token.claim': 'false' } })),
        { name: 'probe mapper control', protocol: 'openid-connect', protocolMapper: 'oidc-hardcoded-claim-mapper',
          config: { 'claim.name': 'probe_control', 'claim.value': 'mapper-active', 'jsonType.label': 'String',
            'id.token.claim': 'true', 'access.token.claim': 'false' } },
        { name: 'probe amr', protocol: 'openid-connect', protocolMapper: 'oidc-amr-mapper',
          config: { 'id.token.claim': 'true', 'access.token.claim': 'false' } },
      ] }],
  }) });
  const keys = await json(`${issuer}/protocol/openid-connect/certs`);
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  try {
    const amr = [];
    for (const remembered of [false, true]) {
      const context = await browser.newContext();
      const first = await authenticate(context, keys, remembered);
      assert.equal(first.gameometro_remember_me, withMapper ? remembered : undefined);
      amr.push(first.amr);
      const cookies = await context.cookies();
      const identity = cookies.find(cookie => cookie.name === 'KEYCLOAK_IDENTITY');
      assert.ok(identity);
      assert.equal(identity.expires > 0, remembered);
      const second = await authenticate(context, keys, null);
      assert.equal(second.gameometro_remember_me, withMapper ? remembered : undefined);
      assert.equal(second.auth_time, first.auth_time, 'SSO must preserve original auth_time');
      assert.equal(second.sid, first.sid, 'Silent entry must reuse the same SSO session');
      await context.close();

      // Restart simulation drops session cookies, keeping persistent cookies only.
      const restarted = await browser.newContext();
      await restarted.addCookies(cookies.filter(cookie => cookie.expires > 0));
      if (remembered) {
        const resumed = await authenticate(restarted, keys, null);
        assert.equal(resumed.gameometro_remember_me, withMapper ? true : undefined);
        assert.equal(resumed.auth_time, first.auth_time);
        assert.equal(resumed.sid, first.sid);
        // A native confirmation can end SSO after the application has lost its ID token.
        // This probes the proposed fallback only, not an implemented BFF logout route.
        const logout = await restarted.newPage();
        await logout.route(url => url.origin + url.pathname === callback, route => route.fulfill({ status: 200, body: 'Logged out' }));
        await logout.goto(`${issuer}/protocol/openid-connect/logout?${new URLSearchParams({
          client_id: clientId, post_logout_redirect_uri: callback })}`);
        await logout.locator('#kc-logout').waitFor();
        assert.ok((await restarted.cookies()).some(cookie => cookie.name === 'KEYCLOAK_IDENTITY'));
        await logout.locator('#kc-logout').click();
        await logout.waitForURL(url => url.origin + url.pathname === callback);
        assert.ok(!(await restarted.cookies()).some(cookie =>
          ['KEYCLOAK_IDENTITY', 'KEYCLOAK_SESSION', 'KEYCLOAK_REMEMBER_ME'].includes(cookie.name)));
        await logout.close();
        await authenticate(restarted, keys, 'form-only');
      } else {
        await authenticate(restarted, keys, 'form-only');
      }
      await restarted.close();
      console.log(`${remembered ? 'Remembered' : 'Ordinary'}: signed tokens, note claims absent, silent auth_time unchanged, restart distinction verified`);
    }
    assert.deepEqual(amr[0], amr[1], 'Reassess AMR if it starts distinguishing Remember Me');
    if (withMapper) {
      const [client] = await json(`${origin}/admin/realms/remember-me-probe/clients?clientId=${clientId}`, { headers });
      // Even an accidentally assigned mapper must not disclose this claim to another client.
      await json(`${origin}/admin/realms/remember-me-probe/clients`, { method: 'POST', headers,
        body: JSON.stringify({ ...client, id: undefined, clientId: 'another-client', secret,
          protocolMappers: client.protocolMappers.map(mapper => ({ ...mapper, id: undefined })) }) });
      const unrelated = await browser.newContext();
      const unrelatedToken = await authenticate(unrelated, keys, true, 'another-client');
      assert.ok(!('gameometro_remember_me' in unrelatedToken));
      await unrelated.close();
      const mapper = client.protocolMappers.find(mapper => mapper.protocolMapper === 'gameometro-remember-me');
      await json(`${origin}/admin/realms/remember-me-probe/clients/${client.id}/protocol-mappers/models/${mapper.id}`, { method: 'DELETE', headers });
      const missing = await browser.newContext();
      const token = await authenticate(missing, keys, true);
      assert.ok(!('gameometro_remember_me' in token), 'Removing the mapper must omit the mode claim');
      await missing.close();
      console.log('SPI: boolean false/true in signed ID tokens, silent/restarted SSO mode preserved, no mode in access token/userinfo; absent mapper omits claim');
    } else {
      console.log('Design gate: tested built-in mappers provide no Remember Me signal; AMR identical');
    }
    console.log('Proposed expired-BFF fallback: native client_id logout requires confirmation and clears remembered SSO');
  } finally {
    await browser.close();
  }
}

async function authenticate(context, keys, remembered, requestedClient = clientId) {
  const page = await context.newPage();
  page.setDefaultTimeout(10_000);
  const responses = [];
  page.on('response', response => {
    const url = new URL(response.url());
    if (response.request().isNavigationRequest()) responses.push({ origin: url.origin, path: url.pathname, status: response.status() });
  });
  const state = randomBytes(24).toString('hex');
  const nonce = randomBytes(24).toString('hex');
  const verifier = randomBytes(32).toString('base64url');
  const params = new URLSearchParams({ client_id: requestedClient, redirect_uri: callback, response_type: 'code',
    scope: 'openid', state, nonce, code_challenge_method: 'S256',
    code_challenge: createHash('sha256').update(verifier).digest('base64url'),
    // Browser flags must not magically become trusted claims or enable Remember Me.
    rememberMe: 'true', remember_me: 'true', probe_note_0: 'true' });
  await page.route(url => url.origin + url.pathname === callback, route => route.fulfill({ status: 200, body: 'Callback received' }));
  await page.goto(`${issuer}/protocol/openid-connect/auth?${params}`);
  if (remembered !== null) {
    await page.locator('#username').waitFor();
    assert.equal(await page.locator('#rememberMe').isChecked(), false, 'Fresh default must be unchecked');
    if (remembered === 'form-only') {
      await page.close();
      return;
    }
    await page.locator('#username').fill('probe-user');
    await page.locator('#password').fill(password);
    if (remembered) await page.locator('#rememberMe').check();
    await page.locator('#kc-login').click();
  }
  try {
    await page.waitForURL(url => url.origin + url.pathname === callback);
  } catch (error) {
    console.error(JSON.stringify({ origin: new URL(page.url()).origin, path: new URL(page.url()).pathname, responses,
      inputIds: await page.locator('input').evaluateAll(inputs => inputs.map(input => input.id)) }));
    throw error;
  }
  const returned = new URL(page.url());
  assert.equal(returned.searchParams.get('state'), state);
  assert.ok(returned.searchParams.get('code'), 'Authorization code required');
  const token = await json(`${issuer}/protocol/openid-connect/token`, { method: 'POST',
    body: new URLSearchParams({ grant_type: 'authorization_code', client_id: requestedClient, client_secret: secret,
      redirect_uri: callback, code: returned.searchParams.get('code'), code_verifier: verifier }) });
  const [header, body, signature] = token.id_token.split('.');
  const accessClaims = JSON.parse(Buffer.from(token.access_token.split('.')[1], 'base64url'));
  assert.ok(!('gameometro_remember_me' in accessClaims));
  const info = await json(`${issuer}/protocol/openid-connect/userinfo`, { headers: { Authorization: `Bearer ${token.access_token}` } });
  assert.ok(!('gameometro_remember_me' in info));
  const metadata = JSON.parse(Buffer.from(header, 'base64url'));
  assert.equal(metadata.alg, 'RS256');
  const key = keys.keys.find(key => key.kid === metadata.kid && key.use === 'sig');
  assert.ok(key);
  assert.ok(verify('RSA-SHA256', Buffer.from(`${header}.${body}`),
    createPublicKey({ key, format: 'jwk' }), Buffer.from(signature, 'base64url')));
  const claims = JSON.parse(Buffer.from(body, 'base64url'));
  assert.equal(claims.iss, issuer);
  assert.ok([claims.aud].flat().includes(requestedClient));
  assert.equal(claims.nonce, nonce);
  assert.ok(claims.exp > Date.now() / 1000);
  assert.equal(typeof claims.auth_time, 'number');
  assert.equal(claims.probe_control, 'mapper-active');
  for (let index = 0; index < noteNames.length; index++) assert.ok(!(`probe_note_${index}` in claims));
  assert.ok(!Object.keys(claims).some(name => /remember/i.test(name) && name !== 'gameometro_remember_me'));
  await page.close();
  return claims;
}

main().catch(error => {
  // Do not dump request/response objects, tokens, credentials or browser cookies on failure.
  console.error('Remember Me trust probe failed; inspect the failed assertion with synthetic data locally');
  console.error(String(error.stack).split('\n').filter(line => /^\s+at .*probe-remember-me-trust\.cjs:\d+:\d+/.test(line)).join('\n'));
  process.exitCode = 1;
});
