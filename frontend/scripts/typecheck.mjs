import { spawnSync } from "node:child_process";
import process from "node:process";
import { fileURLToPath, URL } from "node:url";

// Resolve the native compiler explicitly because both TypeScript packages export tsc.
const compiler = fileURLToPath(new URL("bin/tsc", import.meta.resolve("@typescript/native/package.json")));

for (const project of ["tsconfig.app.json", "tsconfig.node.json"]) {
  const result = spawnSync(process.execPath, [compiler, "--noEmit", "-p", project], {
    cwd: new URL("../", import.meta.url),
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
