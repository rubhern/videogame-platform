import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { repo } from './content.mjs';
test('Serve refuses conflicting routes and Funnel and preserves all other ports',()=>{
  const output = execFileSync('python3',['-c',`
import runpy, copy
m = runpy.run_path('documentation/deploy/documentation-serve')
base = {'TCP': {'443': {'HTTPS': True}, '8443': {'HTTPS': True}, '10000': {'HTTPS': True}}}
good = copy.deepcopy(base)
good['TCP']['9443'] = {'HTTPS': True}
good['Web'] = {'host:9443': {'Handlers': {'/': {'Proxy': 'http://127.0.0.1:4322'}}}}
assert m['inspect'](good)
assert m['without_documentation'](good) == base
assert not m['inspect'](base, True)
for change in [ {'AllowFunnel': {'host:443': True}}, {'TCP': {'9443': {'TCPForward': 'localhost:22'}}} ]:
    bad = copy.deepcopy(good); bad.update(change)
    try: m['inspect'](bad)
    except ValueError: pass
    else: raise AssertionError('unsafe endpoint accepted')
print('ok')
`],{cwd:repo,encoding:'utf8'});
  assert.match(output,/ok/);
});
