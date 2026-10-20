#!/usr/bin/env bash
# Runs in the pinned Playwright image on the disposable client network. Trust is scoped
# to this container; the host certificate store and name resolution stay untouched.
set -Eeuo pipefail
update-ca-certificates >/dev/null
install -m 0644 /run/pki/ca.crt /usr/local/share/ca-certificates/gameometro-test.crt
update-ca-certificates >/dev/null
mkdir -p /root/.pki/nssdb
/run/tooling/nss/usr/bin/certutil -N --empty-password -d sql:/root/.pki/nssdb
/run/tooling/nss/usr/bin/certutil -A -d sql:/root/.pki/nssdb -n gameometro-test -t 'C,,' -i /run/pki/ca.crt
export NODE_EXTRA_CA_CERTS=/run/pki/ca.crt
# Writable test reports stay in the disposable container, never beside repository sources.
mkdir -p /tmp/frontend/tests
cp /work/frontend/package.json /work/frontend/playwright.config.ts /tmp/frontend/
cp /work/frontend/tests/production-identity.spec.ts /tmp/frontend/tests/
ln -s /work/node_modules /tmp/node_modules
cd /tmp/frontend
/work/node_modules/.bin/playwright test tests/production-identity.spec.ts --config=/tmp/frontend/playwright.config.ts --reporter=line --trace=off --workers=1
