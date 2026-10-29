// Bundles the bridge into one script for the app's hidden WebView: `npm ci && node build.mjs [outdir]`.
import { build } from "esbuild";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const outdir = process.argv[2] ?? "../app/src/main/assets/arkade";
mkdirSync(outdir, { recursive: true });
await build({
    entryPoints: ["src/bridge.js"],
    bundle: true,
    format: "iife",
    platform: "browser",
    target: ["chrome100", "safari15"],
    minify: true,
    legalComments: "eof",
    outfile: join(outdir, "arkade-bridge.js"),
    define: { "process.env.NODE_ENV": '"production"' },
    conditions: ["browser"],
});
writeFileSync(
    join(outdir, "index.html"),
    '<!doctype html><html><head><meta charset="utf-8"><script src="arkade-bridge.js"></script></head><body></body></html>\n',
);
