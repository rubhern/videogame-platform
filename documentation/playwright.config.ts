import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir:'./tests',
  fullyParallel:false,
  workers:1,
  retries:0,
  timeout:30_000,
  use:{viewport:{width:1320,height:900},baseURL:process.env.PORTAL_BASE_URL || 'http://127.0.0.1:4321',trace:'retain-on-failure'},
  webServer: process.env.PORTAL_BASE_URL ? undefined : {
    command:'npm run preview -- --port 4321',url:'http://127.0.0.1:4321',reuseExistingServer:!process.env.CI,
  },
});
