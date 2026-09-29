#!/usr/bin/env node
// Turns a PIT mutations.xml (run with fullMutationMatrix=true) into a Markdown report that answers:
// "which of the LLM-generated tests actually kill mutants, and which add nothing?"
//
//   node scripts/llm-testgen/pit-report.mjs target/pit-reports/mutations.xml [target/llm-testgen/manifest.verified.json]
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

export const GENERATED_SUFFIX = 'GeneratedTest';
const DETECTED = new Set(['KILLED', 'TIMED_OUT', 'MEMORY_ERROR', 'RUN_ERROR']);

const tag = (block, name) => {
  const m = new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`).exec(block);
  return m ? decodeXml(m[1].trim()) : '';
};

function decodeXml(s) {
  return s.replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&quot;', '"').replaceAll('&apos;', "'").replaceAll('&amp;', '&');
}

/** The test class named by one PIT killing-test entry (JUnit 5 or JUnit 4 style). */
export function killerClass(entry) {
  const junit5 = /\[class:([^\]]+)\]/.exec(entry);
  if (junit5) return junit5[1];
  const junit4 = /\(([^()]+)\)\s*$/.exec(entry);
  if (junit4) return junit4[1];
  const parts = entry.split('.');
  return parts.length > 1 ? parts.slice(0, -1).join('.') : entry;
}

export function parseMutations(xml) {
  const mutations = [];
  const re = /<mutation\s([^>]*)>([\s\S]*?)<\/mutation>/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const status = /status='([^']*)'/.exec(m[1])?.[1] ?? 'UNKNOWN';
    const body = m[2];
    const killersRaw = tag(body, 'killingTests') || tag(body, 'killingTest');
    const killers = new Set(killersRaw ? killersRaw.split('|').map(killerClass) : []);
    mutations.push({
      status,
      cls: tag(body, 'mutatedClass'),
      method: tag(body, 'mutatedMethod'),
      line: tag(body, 'lineNumber'),
      description: tag(body, 'description'),
      killers,
    });
  }
  return mutations;
}

export function analyze(mutations, generatedClasses = [], suffix = GENERATED_SUFFIX) {
  const isGen = (c) => c.split('.').pop().endsWith(suffix);
  const byStatus = {};
  for (const mu of mutations) byStatus[mu.status] = (byStatus[mu.status] ?? 0) + 1;

  const viable = mutations.filter((mu) => mu.status !== 'NON_VIABLE');
  const detected = viable.filter((mu) => DETECTED.has(mu.status));

  const perGenerated = new Map(generatedClasses.map((c) => [c, { kills: 0, unique: 0 }]));
  let onlyGenerated = 0;
  let onlyExisting = 0;
  let both = 0;
  for (const mu of detected) {
    const killers = [...mu.killers];
    const gen = killers.filter(isGen);
    const existing = killers.filter((k) => !isGen(k));
    if (gen.length && existing.length) both++;
    else if (gen.length) onlyGenerated++;
    else onlyExisting++;
    for (const g of gen) {
      const rec = perGenerated.get(g) ?? { kills: 0, unique: 0 };
      rec.kills++;
      if (killers.length === 1) rec.unique++;
      perGenerated.set(g, rec);
    }
  }
  return {
    total: mutations.length,
    byStatus,
    score: viable.length ? (100 * detected.length) / viable.length : 100,
    onlyGenerated,
    onlyExisting,
    both,
    perGenerated: [...perGenerated.entries()].map(([cls, v]) => ({ cls, ...v })).sort((a, b) => b.kills - a.kills),
    survivors: mutations.filter((mu) => mu.status === 'SURVIVED' || mu.status === 'NO_COVERAGE'),
  };
}

export function render(a) {
  const out = ['### Mutation testing (PIT)', ''];
  if (a.total === 0) return out.concat('No mutants were generated for the changed classes.').join('\n');
  const statuses = Object.entries(a.byStatus).map(([k, v]) => `${k.toLowerCase().replace('_', ' ')}: ${v}`).join(', ');
  out.push(`**Mutation score: ${a.score.toFixed(1)}%** of ${a.total} mutants (${statuses}).`, '');
  out.push('| Killed only by existing tests | Killed by both | Killed **only by generated tests** |');
  out.push('| ---: | ---: | ---: |');
  out.push(`| ${a.onlyExisting} | ${a.both} | ${a.onlyGenerated} |`, '');

  out.push('#### Do the generated tests earn their keep?', '');
  if (a.perGenerated.length === 0) {
    out.push('No generated tests took part in this run.');
  } else {
    out.push('| Generated test | Mutants killed | Killed only by this test |', '| --- | ---: | ---: |');
    for (const g of a.perGenerated) {
      out.push(`| \`${g.cls.replace('com.ledger.', '')}\` | ${g.kills} | ${g.unique} |${g.kills === 0 ? ' ⚠️ kills nothing' : ''}`);
    }
    const idle = a.perGenerated.filter((g) => g.kills === 0);
    if (idle.length) {
      out.push('', `${idle.length} generated test class(es) killed no mutants: they pass, and may add coverage, but ` +
        'they would not catch a regression in this code.');
    }
  }
  if (a.survivors.length) {
    out.push('', '#### Surviving mutants (behaviour no test pins down)', '');
    out.push('| Class | Method | Line | Mutation | Status |', '| --- | --- | ---: | --- | --- |');
    for (const s of a.survivors.slice(0, 15)) {
      out.push(`| \`${s.cls.replace('com.ledger.', '')}\` | ${s.method} | ${s.line} | ${s.description} | ${s.status.toLowerCase().replace('_', ' ')} |`);
    }
    if (a.survivors.length > 15) out.push('', `…and ${a.survivors.length - 15} more (see the uploaded HTML report).`);
  }
  return out.join('\n');
}

function main() {
  const [xmlPath, manifestPath] = process.argv.slice(2);
  if (!xmlPath || !existsSync(xmlPath)) {
    console.log('### Mutation testing (PIT)\n\nNo PIT report was produced.');
    return;
  }
  let generated = [];
  if (manifestPath && existsSync(manifestPath)) {
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    generated = manifest.entries.filter((e) => e.status === 'verified').map((e) => e.fqcn + GENERATED_SUFFIX);
  }
  console.log(render(analyze(parseMutations(readFileSync(xmlPath, 'utf8')), generated)));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main();
}
