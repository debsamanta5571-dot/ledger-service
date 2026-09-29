#!/usr/bin/env node
// Generates a JUnit test class for each changed main-source file using the Anthropic Messages API.
//
//   ANTHROPIC_API_KEY=... node scripts/llm-testgen/generate.mjs --files-from changed.txt [--out target/llm-testgen]
//
// It only WRITES files (tests + manifest.json). It never runs them: that happens in a separate CI job that has
// no access to the API key, because model output is untrusted code.
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import {
  buildPrompt,
  extractJava,
  parseSourcePath,
  skipReason,
  SYSTEM_PROMPT,
  testClassFor,
  testPathFor,
  validateGenerated,
} from './lib.mjs';

const MAX_CONTEXT_FILES = 5;
const MAX_CONTEXT_CHARS = 5000;
const MAX_SOURCE_CHARS = 30000;

/** Sibling files whose class name appears in the source; gives the model real signatures to work from. */
export function gatherContext(path, source, readDir = readSiblings) {
  const info = parseSourcePath(path);
  return readDir(dirname(path))
    .filter((f) => f.path.replaceAll('\\', '/') !== path.replaceAll('\\', '/'))
    .filter((f) => new RegExp(`\\b${f.name}\\b`).test(source))
    .slice(0, MAX_CONTEXT_FILES)
    .map((f) => ({ path: f.path, text: f.text.slice(0, MAX_CONTEXT_CHARS) }))
    .filter(() => info !== null);
}

function readSiblings(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((n) => n.endsWith('.java'))
    .map((n) => ({ name: n.replace(/\.java$/, ''), path: join(dir, n).replaceAll('\\', '/'), text: readFileSync(join(dir, n), 'utf8') }));
}

/**
 * Produces a manifest entry for one source file. `callModel(system, user)` returns the reply text; it is injected so
 * the pipeline can be tested without the network.
 */
export async function generateOne({ path, source, callModel, context = [] }) {
  const skip = skipReason(path, source);
  if (skip) return { source: path, status: 'skipped', reason: skip };
  if (source.length > MAX_SOURCE_CHARS) return { source: path, status: 'skipped', reason: 'file too large for one prompt' };

  const reply = await callModel(SYSTEM_PROMPT, buildPrompt({ path, source, context }));
  const code = extractJava(reply);
  const problems = validateGenerated(code, path);
  if (problems.length > 0) return { source: path, status: 'rejected', reason: problems.join('; ') };
  return {
    source: path,
    status: 'generated',
    testFile: testPathFor(path),
    testClass: testClassFor(path),
    fqcn: parseSourcePath(path).fqcn,
    code,
  };
}

export function makeAnthropicCaller({ apiKey, model, fetchImpl = fetch, sleep = (ms) => new Promise((r) => setTimeout(r, ms)) }) {
  return async function callModel(system, user) {
    for (let attempt = 1; attempt <= 4; attempt++) {
      const res = await fetchImpl('https://api.anthropic.com/v1/messages', {
        method: 'POST',
        headers: { 'x-api-key': apiKey, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
        body: JSON.stringify({ model, max_tokens: 4096, system, messages: [{ role: 'user', content: user }] }),
      });
      if (res.ok) {
        const data = await res.json();
        return (data.content ?? []).filter((b) => b.type === 'text').map((b) => b.text).join('');
      }
      const retryable = res.status === 429 || res.status >= 500;
      if (!retryable || attempt === 4) {
        const err = new Error(`Anthropic API error ${res.status}`);
        err.fatal = res.status === 401 || res.status === 403;
        throw err;
      }
      await sleep(1000 * 2 ** attempt);
    }
    throw new Error('unreachable');
  };
}

function parseArgs(argv) {
  const args = { out: 'target/llm-testgen', filesFrom: null };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--files-from') args.filesFrom = argv[++i];
    else if (argv[i] === '--out') args.out = argv[++i];
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    console.error('ANTHROPIC_API_KEY is not set');
    process.exit(2);
  }
  if (!args.filesFrom) {
    console.error('usage: generate.mjs --files-from <file with one path per line> [--out dir]');
    process.exit(2);
  }
  const model = process.env.ANTHROPIC_MODEL || 'claude-sonnet-5-5';
  const callModel = makeAnthropicCaller({ apiKey, model });
  const files = readFileSync(args.filesFrom, 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean);

  mkdirSync(args.out, { recursive: true });
  const manifest = { model, entries: [] };
  for (const path of files) {
    if (!existsSync(path)) {
      manifest.entries.push({ source: path, status: 'skipped', reason: 'file no longer exists' });
      continue;
    }
    const source = readFileSync(path, 'utf8');
    try {
      const entry = await generateOne({ path, source, callModel, context: gatherContext(path, source) });
      if (entry.status === 'generated') {
        const dest = join(args.out, entry.testFile);
        mkdirSync(dirname(dest), { recursive: true });
        writeFileSync(dest, entry.code);
        delete entry.code;
      }
      manifest.entries.push(entry);
    } catch (e) {
      if (e.fatal) {
        console.error(`${e.message} — check the ANTHROPIC_API_KEY secret`);
        process.exit(1);
      }
      manifest.entries.push({ source: path, status: 'error', reason: e.message });
    }
    const last = manifest.entries.at(-1);
    console.log(`${last.status.padEnd(9)} ${path}${last.reason ? `  (${last.reason})` : ''}`);
  }
  writeFileSync(join(args.out, 'manifest.json'), JSON.stringify(manifest, null, 2));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main();
}
