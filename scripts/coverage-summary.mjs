#!/usr/bin/env node
// Prints a Markdown coverage table from a JaCoCo CSV report (used for the GitHub Actions job summary).
// Usage: node scripts/coverage-summary.mjs target/site/jacoco/jacoco.csv
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

export function parseJacocoCsv(text) {
  const [header, ...rows] = text.trim().split(/\r?\n/);
  const cols = header.split(',');
  const idx = Object.fromEntries(cols.map((c, i) => [c, i]));
  return rows.map((line) => {
    const f = line.split(',');
    const n = (name) => Number(f[idx[name]]);
    return {
      cls: `${f[idx.PACKAGE]}.${f[idx.CLASS]}`,
      lineMissed: n('LINE_MISSED'),
      lineCovered: n('LINE_COVERED'),
      branchMissed: n('BRANCH_MISSED'),
      branchCovered: n('BRANCH_COVERED'),
    };
  });
}

export function totals(rows) {
  const sum = (k) => rows.reduce((a, r) => a + r[k], 0);
  return {
    lineMissed: sum('lineMissed'),
    lineCovered: sum('lineCovered'),
    branchMissed: sum('branchMissed'),
    branchCovered: sum('branchCovered'),
  };
}

export const pct = (covered, missed) => (covered + missed === 0 ? 100 : (100 * covered) / (covered + missed));

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1]);
if (isMain) {
  const file = process.argv[2];
  if (!file || !existsSync(file)) {
    console.log('## Coverage\n\nNo JaCoCo report found.');
    process.exit(0);
  }
  const rows = parseJacocoCsv(readFileSync(file, 'utf8'));
  const t = totals(rows);
  console.log('## Coverage (JaCoCo)\n');
  console.log('| Metric | Covered | Missed | Coverage |');
  console.log('| --- | ---: | ---: | ---: |');
  console.log(`| Lines | ${t.lineCovered} | ${t.lineMissed} | ${pct(t.lineCovered, t.lineMissed).toFixed(1)}% |`);
  console.log(`| Branches | ${t.branchCovered} | ${t.branchMissed} | ${pct(t.branchCovered, t.branchMissed).toFixed(1)}% |`);
}
