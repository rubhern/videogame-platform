// Disposable realm only. Never prints identity records, event bodies, credentials or mail links.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
const require=createRequire('/work/package.json');
// Use the repository lockfile's Playwright with the matching pinned browser runtime.
const { chromium }=require('playwright');
const origin='http://keycloak:8080';
const realm=origin+'/realms/videogame-platform';
const fixturePassword=process.env.FIXTURE_PASSWORD;
assert(fixturePassword);
async function admin(path) {
  const token=await (await fetch(origin+'/realms/master/protocol/openid-connect/token',{
    method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:new URLSearchParams({grant_type:'password',client_id:'admin-cli',username:'fixture-admin',password:fixturePassword})
  })).json();
  assert(token.access_token);
  const response=await fetch(origin+'/admin/realms/videogame-platform'+path,{headers:{Authorization:'Bearer '+token.access_token}});
  assert(response.ok);
  return response.json();
}
async function snapshot() {
  const r=await admin('');
  const users=await admin('/users?max=20');
  return {settings:Object.fromEntries(['realm','enabled','registrationAllowed','resetPasswordAllowed','verifyEmail','loginTheme','emailTheme'].map(k=>[k,r[k]])),
    users:users.map(u=>({id:u.id,username:u.username,emailVerified:u.emailVerified,enabled:u.enabled})).sort((a,b)=>a.username.localeCompare(b.username))};
}
async function wait(check, label) {
  for(let attempt=0;attempt<180;attempt++) {
    try { const result=await check(); if(result) return result; } catch {}
    await new Promise(resolve=>setTimeout(resolve,700));
  }
  throw new Error(label);
}
await wait(async()=> (await fetch(realm+'/.well-known/openid-configuration')).ok,'Keycloak not ready');
async function metrics() {
  const response=await fetch('http://keycloak:9000/metrics');
  assert(response.ok);
  return (await response.text()).split('\n').filter(line=>line.startsWith('keycloak_user_events_total{'));
}
function value(lines, event) {
  return lines.filter(line=>line.includes('event="'+event+'"')&&line.includes('error=""')&&line.includes('realm="videogame-platform"'))
    .reduce((total,line)=>total+Number(line.split('}')[1]),0);
}
async function query(expression) {
  const response=await fetch('http://prometheus:9090/api/v1/query?'+new URLSearchParams({query:expression}));
  assert(response.ok);
  const body=await response.json();
  assert.equal(body.status,'success');
  return body.data.result;
}
async function registrationRule() {
  const response=await fetch('http://grafana:3000/api/prometheus/grafana/api/v1/rules',{
    headers:{Authorization:'Basic '+Buffer.from('fixture-admin:'+fixturePassword).toString('base64')}
  });
  assert(response.ok);
  const body=await response.json();
  const rule=body.data.groups.flatMap(g=>g.rules).find(r=>r.name==='Registration activity observed');
  // The native API also retains Normal (NoData) instances after a lazy series appears.
  if(rule) rule.firingAlerts=(rule.alerts ?? []).filter(a=>a.state==='Alerting');
  return rule;
}
if(process.env.PROOF_RESTART==='true') {
  const lines=await metrics();
  assert.equal(value(lines,'register'),0);
  assert.equal(value(lines,'verify_email'),0);
  assert.deepEqual(await snapshot(),JSON.parse(readFileSync('/fixture/identity-before.json','utf8')));
  console.log('Optimized rebuilt image retained exact fixture account IDs and realm settings; event counters reset, not lifetime totals.');
  process.exit(0);
}
assert.equal(value(await metrics(),'register'),0,'Imported/admin accounts are not registration events');
await wait(async()=> (await query('up{job="keycloak-product"}'))[0]?.value[1]==='1','Internal Keycloak scrape unavailable');
const quiet=await query('sum(increase(keycloak_user_events_total{job="keycloak-product",event="register",error=""}[5m])) or ((max(up{job="keycloak-product"}) == 1) * 0)');
assert.equal(quiet[0].value[1],'0','Healthy configured source with no events should show zero');
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
await wait(async()=> (await registrationRule())?.health==='ok','Native Grafana product rule unavailable');
let firingFingerprint;
try {
  for(let index=1;index<=3;index++) {
    const context=await browser.newContext();
    const page=await context.newPage();
    const params=new URLSearchParams({client_id:'fixture',redirect_uri:origin+'/callback',response_type:'code',scope:'openid',state:'fixture'});
    await page.goto(realm+'/protocol/openid-connect/auth?'+params);
    await page.getByRole('link',{name:/^(Register|Crear cuenta)$/}).click();
    await page.locator('#username').fill(index===1?'seed':'fixture'+index);
    await page.locator('#email').fill('fixture'+index+'@example.invalid');
    await page.locator('#firstName').fill('Fixture');
    await page.locator('#lastName').fill('Test');
    // Keycloak 26.7 can defer password creation until after email verification.
    if(await page.locator('#password').count()) {
      await page.locator('#password').fill('fixture-password-123');
      await page.locator('#password-confirm').fill('fixture-password-123');
    }
    await page.getByRole('button',{name:/^(Register|Crear cuenta)$/}).click();
    if(index===1) {
      assert.equal(value(await metrics(),'register'),0,'Rejected registration cannot increment successful register');
      await page.locator('#username').fill('fixture'+index);
      if(await page.locator('#password').count()) {
        await page.locator('#password').fill('fixture-password-123');
        await page.locator('#password-confirm').fill('fixture-password-123');
      }
      await page.getByRole('button',{name:/^(Register|Crear cuenta)$/}).click();
    }
    await page.waitForURL(/login-actions/);
    await wait(async()=>value(await metrics(),'register')===index,'Successful registration not counted');
    const all=await wait(async()=>{
      const response=await fetch('http://mailpit:8025/api/v1/messages');
      const body=await response.json();
      return body.messages?.find(message=>message.To.some(to=>to.Address==='fixture'+index+'@example.invalid'));
    },'Verification email missing');
    const mail=await (await fetch('http://mailpit:8025/api/v1/message/'+all.ID)).json();
    const link=mail.Text.match(/http:\/\/keycloak:8080\/\S+/)?.[0];
    assert(link,'Verification link missing');
    await page.goto(link.replaceAll('&amp;','&'));
    await wait(async()=>value(await metrics(),'verify_email')===index,'Verified email not counted');
    if(await page.locator('#password-new').count()) {
      await page.locator('#password-new').fill('fixture-password-123');
      await page.locator('#password-confirm').fill('fixture-password-123');
      await page.locator('button[type="submit"], input[type="submit"]').click();
    }
    await context.close();
    await wait(async()=> (await query('keycloak_user_events_total{job="keycloak-product",event="register",error=""}'))[0]?.value[1]===String(index),
      'Registration baseline was not scraped before the next event');
    if(index===1) {
      const quiet=await query('sum(increase(keycloak_user_events_total{job="keycloak-product",event="register",error=""}[5m])) or ((max(up{job="keycloak-product"}) == 1) * 0)');
      assert.equal(Number(quiet[0].value[1]),0,'A first lazy sample cannot prove an increment');
      console.log('First successful registration exports a raw count but cannot establish a sampled increase: per-user delivery is not guaranteed.');
    }
    if(index===2) {
      const rule=await wait(async()=>{
        const r=await registrationRule();
        return r?.state==='firing' && r.firingAlerts.length===1 && r;
      },'Observed registration increase did not fire the native Grafana rule');
      assert(Number(rule.firingAlerts[0].annotations.observed_registrations)>=1);
      firingFingerprint=JSON.stringify(rule.firingAlerts[0].labels);
      console.log('Actual optimized registration metrics fire one native Grafana activity instance with an estimated count.');
    }
    if(index===3) {
      const rule=await registrationRule();
      assert.equal(rule.state,'firing');
      assert.equal(rule.firingAlerts.length,1,'A registration while firing does not create an individual alert');
      assert.equal(JSON.stringify(rule.firingAlerts[0].labels),firingFingerprint);
    }
  }
  const adminToken=await (await fetch(origin+'/realms/master/protocol/openid-connect/token',{
    method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:new URLSearchParams({grant_type:'password',client_id:'admin-cli',username:'fixture-admin',password:fixturePassword})
  })).json();
  assert(adminToken.access_token);
  const adminCreated=await fetch(origin+'/admin/realms/videogame-platform/users',{
    method:'POST',headers:{Authorization:'Bearer '+adminToken.access_token,'Content-Type':'application/json'},
    body:JSON.stringify({username:'admin-created',enabled:true,emailVerified:true})
  });
  assert.equal(adminCreated.status,201);
  const lines=await metrics();
  assert(lines.every(line=>/^(keycloak_user_events_total)\{(?:[a-z_]+="[^"]*",?)+\} [0-9.]+$/.test(line)));
  for(const line of lines) {
    const keys=[...line.matchAll(/([a-z_]+)="/g)].map(match=>match[1]);
    assert(keys.every(key=>['realm','event','error'].includes(key)),'Unexpected personal or unbounded tag');
  }
  assert.equal(value(lines,'register'),3);
  assert.equal(value(lines,'verify_email'),3);
  assert(lines.some(line=>line.includes('event="register"')&&!line.includes('error=""')),'Fixture did not exercise a failed registration event');
  await wait(async()=> (await query('keycloak_user_events_total{job="keycloak-product",event="register",error=""}'))[0]?.value[1]==='3','Successful registrations not scraped');
  const scraped=await query('keycloak_user_events_total{job="keycloak-product"}');
  assert.equal(scraped.length,2);
  assert(scraped.every(sample=>(sample.metric.error ?? '')==='' && sample.metric.realm==='videogame-platform'));
  assert(scraped.every(sample=>Object.keys(sample.metric).every(key=>['__name__','event','error','realm','job','instance'].includes(key))));
  const security=await query('keycloak_user_events_total{job="keycloak-security"}');
  assert(security.some(sample=>sample.metric.event==='register' && sample.metric.error==='username_in_use'),
    'The separate security scrape must retain the rejected registration signal');
  assert(security.every(sample=>sample.metric.realm==='videogame-platform' &&
    Object.keys(sample.metric).every(key=>['__name__','event','error','realm','job','instance'].includes(key))),
    'Unexpected personal or unbounded security metric tag');
  console.log('Actual Prometheus scrape retains only two successful event series; quiet configured source is zero.');
  // Grafana returns the provisioned series name, never the PromQL, for the real registration panels.
  const growth=JSON.parse(readFileSync('/work/deploy/private-dev/grafana/dashboards/vgp-growth.json','utf8'));
  for(const panel of growth.panels.filter(p=>p.targets?.some(t=>t.expr?.includes('event="register"')))) {
    const response=await fetch('http://grafana:3000/api/ds/query',{method:'POST',
      headers:{Authorization:'Basic '+Buffer.from('fixture-admin:'+fixturePassword).toString('base64'),'Content-Type':'application/json'},
      body:JSON.stringify({from:'now-1h',to:'now',queries:panel.targets.map(t=>({...t,expr:t.expr.replace('$__range','1h'),intervalMs:15000,maxDataPoints:100}))})});
    assert(response.ok);
    const frames=Object.values((await response.json()).results).flatMap(result=>result.frames ?? []);
    const names=frames.flatMap(frame=>frame.schema.fields.slice(1).map(field=>field.config?.displayNameFromDS));
    assert(names.length>0 && names.every(name=>name==='Registrations'),'Registration series name is not human-readable');
  }
  console.log('Grafana names real registration series "Registrations", not their PromQL.');
  console.log('Three successful registrations and email verifications counted; failed/admin/imported accounts and PII excluded.');
  // The real private theme/realm password-recovery flow still works in the optimized image.
  const context=await browser.newContext();
  const page=await context.newPage();
  const auth=realm+'/protocol/openid-connect/auth?'+new URLSearchParams({client_id:'fixture',redirect_uri:origin+'/callback',response_type:'code',scope:'openid',state:'recovery-fixture'});
  const beforeMail=await (await fetch('http://mailpit:8025/api/v1/messages')).json();
  const oldIds=new Set(beforeMail.messages.map(m=>m.ID));
  await page.goto(auth);
  await page.locator('a[href*="reset-credentials"]').click();
  await page.locator('#username').fill('fixture1');
  await page.locator('button[type="submit"], input[type="submit"]').click();
  const reset=await wait(async()=>{
    const body=await (await fetch('http://mailpit:8025/api/v1/messages')).json();
    return body.messages.find(m=>!oldIds.has(m.ID)&&m.To.some(to=>to.Address==='fixture1@example.invalid'));
  },'Password recovery email missing');
  const body=await (await fetch('http://mailpit:8025/api/v1/message/'+reset.ID)).json();
  const resetLink=body.Text.match(/http:\/\/keycloak:8080\/\S+/)?.[0];
  assert(resetLink);
  await page.goto(resetLink.replaceAll('&amp;','&'));
  await page.locator('#password-new').fill('fixture-reset-password-123');
  await page.locator('#password-confirm').fill('fixture-reset-password-123');
  await page.locator('button[type="submit"], input[type="submit"]').click();
  await context.close();
  const loginContext=await browser.newContext();
  const loginPage=await loginContext.newPage();
  await loginPage.goto(auth);
  await loginPage.locator('#username').fill('fixture1');
  await loginPage.locator('#password').fill('fixture-reset-password-123');
  await loginPage.locator('button[type="submit"], input[type="submit"]').click();
  await loginPage.waitForURL(url=>url.pathname==='/callback'&&url.searchParams.has('code'));
  await loginContext.close();
  assert.equal(value(await metrics(),'register'),3,'Login/password recovery cannot count as registration');
  writeFileSync('/fixture/identity-before.json',JSON.stringify(await snapshot()));
  console.log('Private Gameómetro theme: successful self-registration, verification, password recovery and subsequent login passed.');
} finally { await browser.close(); }
