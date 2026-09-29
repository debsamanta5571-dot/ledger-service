#!/usr/bin/env node
// Compares two JaCoCo CSV reports and prints the coverage delta as Markdown.
//
//   node scripts/llm-testgen/coverage-delta.mjs baseline.csv after.csv [com.ledger.api.TokenBucket,...]
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { parseJacocoCsv, totals, pct } from '../coverage-summary.mjs';

function rates(t) {
  return { line: pct(t.lineCovered, t.lineMissed), branch: pct(t.branchCovered, t.branchMissed) };
}

export function compare(baseRows, afterRows, classes = []) {
  const byName = (rows) => new Map(rows.map((r) => [r.cls, r]));
  const base = byName(baseRows);
  const after = byName(afterRows);
  return {
    overall: { before: rates(totals(baseRows)), after: rates(totals(afterRows)) },
    perClass: classes.map((cls) => {
      const b = base.get(cls);
      const a = after.get(cls);
      return { cls, before: b ? rates(b) : null, after: a ? rates(a) : null };
    }),
  };
}

const fmt = (n) => (n === null || n === undefined ? 'n/a' : `${n.toFixed(1)}%`);
const delta = (b, a) => (b === null || a === null ? 'n/a' : `${a - b >= 0 ? '+' : ''}${(a - b).toFixed(1)} pts`);

export function render(result) {
  const { overall, perClass } = result;
  const out = ['### Coverage delta (unit tests only)', ''];
  out.push('| Scope | Line before | Line after | Δ line | Branch before | Branch after | Δ branch |');
  out.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: |');
  const row = (label, b, a) =>
    `| ${label} | ${fmt(b?.line)} | ${fmt(a?.line)} | ${delta(b?.line ?? null, a?.line ?? null)} | ` +
    `${fmt(b?.branch)} | ${fmt(a?.branch)} | ${delta(b?.branch ?? null, a?.branch ?? null)} |`;
  out.push(row('**Whole project**', overall.before, overall.after));
  for (const c of perClass) out.push(row(`\`${c.cls.replace('com.ledger.', '')}\``, c.before, c.after));
  return out.join('\n');
}

function main() {
  const [baseFile, afterFile, classList] = process.argv.slice(2);
  if (!baseFile || !afterFile) {
    console.error('usage: coverage-delta.mjs baseline.csv after.csv [classes]');
    process.exit(2);
  }
  const classes = classList ? classList.split(',').filter(Boolean) : [];
  const result = compare(
    parseJacocoCsv(readFileSync(baseFile, 'utf8')),
    parseJacocoCsv(readFileSync(afterFile, 'utf8')),
    classes,
  );
  console.log(render(result));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main();
}
