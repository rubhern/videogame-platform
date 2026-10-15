// Disposable realm only. Never prints identity records, event bodies, credentials or mail links.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire('/work/package.json');
// Use the repository lockfile's Playwright with the matching pinned browser runtime.
const { chromium }=require('playwright');
const origin='http://keycloak:8080';
const realm=origin+'/realms/videogame-platform';
async function wait(check, label) {
  for(let attempt=0;attempt<100;attempt++) {
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
if(process.env.PROOF_RESTART==='true') {
  const lines=await metrics();
  assert.equal(value(lines,'register'),0);
  assert.equal(value(lines,'verify_email'),0);
  console.log('Keycloak counters reset after recreation with persisted accounts; no lifetime-total claim.');
  process.exit(0);
}
assert.equal(value(await metrics(),'register'),0,'Imported/admin accounts are not registration events');
await wait(async()=> (await query('up{job="keycloak-product"}'))[0]?.value[1]==='1','Internal Keycloak scrape unavailable');
const quiet=await query('sum(increase(keycloak_user_events_total{job="keycloak-product",event="register",error=""}[5m])) or ((max(up{job="keycloak-product"}) == 1) * 0)');
assert.equal(quiet[0].value[1],'0','Healthy configured source with no events should show zero');
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
try {
  for(let index=1;index<=2;index++) {
    const context=await browser.newContext();
    const page=await context.newPage();
    const params=new URLSearchParams({client_id:'fixture',redirect_uri:origin+'/callback',response_type:'code',scope:'openid',state:'fixture'});
    await page.goto(realm+'/protocol/openid-connect/auth?'+params);
    await page.getByRole('link',{name:'Register',exact:true}).click();
    await page.locator('#username').fill(index===1?'seed':'fixture'+index);
    await page.locator('#email').fill('fixture'+index+'@example.invalid');
    await page.locator('#firstName').fill('Fixture');
    await page.locator('#lastName').fill('Test');
    // Keycloak 26.7 can defer password creation until after email verification.
    if(await page.locator('#password').count()) {
      await page.locator('#password').fill('fixture-password-123');
      await page.locator('#password-confirm').fill('fixture-password-123');
    }
    await page.getByRole('button',{name:'Register',exact:true}).click();
    if(index===1) {
      assert.equal(value(await metrics(),'register'),0,'Rejected registration cannot increment successful register');
      await page.locator('#username').fill('fixture'+index);
      if(await page.locator('#password').count()) {
        await page.locator('#password').fill('fixture-password-123');
        await page.locator('#password-confirm').fill('fixture-password-123');
      }
      await page.getByRole('button',{name:'Register',exact:true}).click();
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
      await page.getByRole('button',{name:'Submit',exact:true}).click();
    }
    await context.close();
  }
  const adminToken=await (await fetch(origin+'/realms/master/protocol/openid-connect/token',{
    method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:new URLSearchParams({grant_type:'password',client_id:'admin-cli',username:'fixture-admin',password:'fixture-password'})
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
  assert.equal(value(lines,'register'),2);
  assert.equal(value(lines,'verify_email'),2);
  assert(lines.some(line=>line.includes('event="register"')&&!line.includes('error=""')),'Fixture did not exercise a failed registration event');
  await wait(async()=> (await query('keycloak_user_events_total{job="keycloak-product",event="register",error=""}'))[0]?.value[1]==='2','Successful registrations not scraped');
  const scraped=await query('keycloak_user_events_total');
  assert.equal(scraped.length,2);
  assert(scraped.every(sample=>(sample.metric.error ?? '')==='' && sample.metric.realm==='videogame-platform'));
  assert(scraped.every(sample=>Object.keys(sample.metric).every(key=>['__name__','event','error','realm','job','instance'].includes(key))));
  console.log('Actual Prometheus scrape retains only two successful event series; quiet configured source is zero.');
  console.log('Two successful registrations and email verifications counted; failed registration is separately tagged; admin/imported accounts excluded; only realm/event/error labels.');
} finally { await browser.close(); }
