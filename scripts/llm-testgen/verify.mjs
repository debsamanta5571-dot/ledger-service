#!/usr/bin/env node
// Runs every generated test class on its own and keeps only the ones that compile and pass.
//
//   node scripts/llm-testgen/verify.mjs [--manifest target/llm-testgen/manifest.json]
//
// A generated test that FAILS is dropped from the run but reported prominently: it is either a bad test or a
// real bug in the code, and a human should look. Prints a Markdown table (for the job summary) to stdout.
import { readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

/** Pure: decide what a Maven run means. */
export function classifyMavenRun(exitCode, output) {
  if (exitCode === 0) return { status: 'verified' };
  if (/COMPILATION ERROR|cannot find symbol|package .* does not exist/i.test(output)) {
    return { status: 'rejected-compile', detail: firstErrors(output) };
  }
  if (/Tests run:.*(Failures: [1-9]|Errors: [1-9])|There are test failures|FAILURE!/i.test(output)) {
    return { status: 'rejected-failing', detail: firstErrors(output) };
  }
  return { status: 'rejected-error', detail: firstErrors(output) };
}

function firstErrors(output, max = 6) {
  return output
    .split(/\r?\n/)
    .filter((l) => /\[ERROR\]/.test(l))
    .slice(0, max)
    .map((l) => l.replace(/\[ERROR\]\s*/, '').trim())
    .join(' | ')
    .slice(0, 600);
}

export function renderVerification(entries) {
  const rows = entries.filter((e) => e.status !== 'skipped');
  const count = (s) => rows.filter((e) => e.status === s).length;
  const out = ['### Generated tests', ''];
  out.push(`${count('verified')} verified, ${rows.filter((e) => e.status.startsWith('rejected')).length} rejected, ` +
    `${entries.filter((e) => e.status === 'skipped').length} skipped.`, '');
  out.push('| Source | Result | Detail |', '| --- | --- | --- |');
  for (const e of entries) {
    const icon = { verified: '✅ verified', skipped: '⏭ skipped' }[e.status] ?? `❌ ${e.status}`;
    out.push(`| \`${e.source.replace('src/main/java/', '')}\` | ${icon} | ${(e.detail ?? e.reason ?? '').replaceAll('|', '/')} |`);
  }
  const failing = entries.filter((e) => e.status === 'rejected-failing');
  if (failing.length) {
    out.push('', '> ⚠️ **Failing generated tests** may indicate a real bug rather than a bad test. Review: ' +
      failing.map((e) => `\`${e.testClass}\``).join(', '));
  }
  return out.join('\n');
}

function main() {
  const i = process.argv.indexOf('--manifest');
  const manifestPath = i > -1 ? process.argv[i + 1] : 'target/llm-testgen/manifest.json';
  if (!existsSync(manifestPath)) {
    console.log('No manifest found; nothing to verify.');
    return;
  }
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  for (const entry of manifest.entries) {
    if (entry.status !== 'generated') continue;
    if (!existsSync(entry.testFile)) {
      entry.status = 'rejected-error';
      entry.detail = 'generated file missing';
      continue;
    }
    const run = spawnSync(
      'mvn',
      ['-B', '-q', 'test', `-Dtest=${entry.testClass}`, '-Djacoco.skip=true', '-Dsurefire.failIfNoSpecifiedTests=false'],
      { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, shell: process.platform === 'win32' },
    );
    const result = classifyMavenRun(run.status ?? 1, `${run.stdout}\n${run.stderr}`);
    entry.status = result.status;
    if (result.detail) entry.detail = result.detail;
    if (result.status !== 'verified') rmSync(entry.testFile, { force: true });
    console.error(`${result.status.padEnd(17)} ${entry.testClass}`);
  }
  writeFileSync(manifestPath.replace(/\.json$/, '.verified.json'), JSON.stringify(manifest, null, 2));
  console.log(renderVerification(manifest.entries));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main();
}
