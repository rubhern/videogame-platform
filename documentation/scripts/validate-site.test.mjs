import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { validateSite } from './validate-site.mjs';
test('published validation catches dangling anchors, missing assets and external runtime resources',()=>{
  const root = mkdtempSync(path.join(os.tmpdir(),'portal-links-'));
  try {
    mkdirSync(path.join(root,'pagefind')); writeFileSync(path.join(root,'pagefind/pagefind.js'),'');
    writeFileSync(path.join(root,'index.html'),'<h1>Page</h1><h2 id="known">Known</h2><a href="#known">Valid</a>');
    assert.equal(validateSite(root),1);
    for (const html of ['<a href="#missing">Broken</a>','<img src="/absent.svg">','<script src="https://cdn.example/script.js"></script>']) {
      writeFileSync(path.join(root,'index.html'),'<h1>Page</h1>'+html);
      assert.throws(()=>validateSite(root),/missing|external runtime resource/);
    }
  } finally { rmSync(root,{recursive:true,force:true}); }
});
