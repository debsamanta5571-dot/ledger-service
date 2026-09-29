import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyMavenRun, renderVerification } from './verify.mjs';
import { compare, render as renderDelta } from './coverage-delta.mjs';
import { parseMutations, analyze, render as renderPit, killerClass } from './pit-report.mjs';

// ---------- verify ----------

test('classifyMavenRun tells success, compile errors, failing tests and other errors apart', () => {
  assert.equal(classifyMavenRun(0, '').status, 'verified');

  const compile = classifyMavenRun(1, '[ERROR] COMPILATION ERROR :\n[ERROR] /x/Foo.java:[10,5] cannot find symbol');
  assert.equal(compile.status, 'rejected-compile');
  assert.match(compile.detail, /cannot find symbol/);

  const failing = classifyMavenRun(1, '[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0\n[ERROR] FooTest.a:12 expected 1 but was 2');
  assert.equal(failing.status, 'rejected-failing');

  assert.equal(classifyMavenRun(1, '[ERROR] Failed to execute goal something else').status, 'rejected-error');
});

test('renderVerification summarizes results and flags failing tests as possible bugs', () => {
  const md = renderVerification([
    { source: 'src/main/java/com/ledger/A.java', status: 'verified', testClass: 'AGeneratedTest' },
    { source: 'src/main/java/com/ledger/B.java', status: 'rejected-failing', testClass: 'BGeneratedTest', detail: 'expected 1 | but 2' },
    { source: 'src/main/java/com/ledger/C.java', status: 'skipped', reason: 'exception type' },
  ]);
  assert.match(md, /1 verified, 1 rejected, 1 skipped/);
  assert.match(md, /may indicate a real bug/);
  assert.match(md, /BGeneratedTest/);
  assert.ok(!md.includes('| expected 1 | but 2 |'), 'pipes in details must not break the table');
});

// ---------- coverage delta ----------

const row = (cls, lm, lc, bm, bc) => ({ cls, lineMissed: lm, lineCovered: lc, branchMissed: bm, branchCovered: bc });

test('compare computes overall and per-class rates', () => {
  const before = [row('com.ledger.A', 5, 5, 2, 2), row('com.ledger.B', 0, 10, 0, 0)];
  const after = [row('com.ledger.A', 1, 9, 0, 4), row('com.ledger.B', 0, 10, 0, 0)];
  const r = compare(before, after, ['com.ledger.A', 'com.ledger.Missing']);

  assert.equal(r.overall.before.line, 75);
  assert.equal(r.overall.after.line, 95);
  assert.equal(r.perClass[0].before.line, 50);
  assert.equal(r.perClass[0].after.line, 90);
  assert.equal(r.perClass[0].after.branch, 100);
  assert.equal(r.perClass[1].before, null);

  const md = renderDelta(r);
  assert.match(md, /\+20\.0 pts/);
  assert.match(md, /`A`/);
  assert.match(md, /n\/a/);
});

// ---------- PIT ----------

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<mutations>
<mutation detected='true' status='KILLED' numberOfTestsRun='3'><sourceFile>TokenBucket.java</sourceFile><mutatedClass>com.ledger.api.TokenBucket</mutatedClass><mutatedMethod>tryConsume</mutatedMethod><methodDescription>()V</methodDescription><lineNumber>40</lineNumber><mutator>org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator</mutator><indexes><index>1</index></indexes><blocks><block>1</block></blocks><killingTests>com.ledger.api.TokenBucketTest.[engine:junit-jupiter]/[class:com.ledger.api.TokenBucketTest]/[method:a()]|com.ledger.api.TokenBucketGeneratedTest.[engine:junit-jupiter]/[class:com.ledger.api.TokenBucketGeneratedTest]/[method:b()]</killingTests><description>changed conditional boundary</description></mutation>
<mutation detected='true' status='KILLED' numberOfTestsRun='1'><sourceFile>TokenBucket.java</sourceFile><mutatedClass>com.ledger.api.TokenBucket</mutatedClass><mutatedMethod>capacity</mutatedMethod><methodDescription>()I</methodDescription><lineNumber>30</lineNumber><mutator>x.PrimitiveReturnsMutator</mutator><indexes><index>2</index></indexes><blocks><block>1</block></blocks><killingTests>com.ledger.api.TokenBucketGeneratedTest.[engine:junit-jupiter]/[class:com.ledger.api.TokenBucketGeneratedTest]/[method:c()]</killingTests><description>replaced int return with 0</description></mutation>
<mutation detected='true' status='KILLED' numberOfTestsRun='1'><sourceFile>TokenBucket.java</sourceFile><mutatedClass>com.ledger.api.TokenBucket</mutatedClass><mutatedMethod>tryConsume</mutatedMethod><methodDescription>()V</methodDescription><lineNumber>44</lineNumber><mutator>x.MathMutator</mutator><indexes><index>3</index></indexes><blocks><block>1</block></blocks><killingTest>com.ledger.api.TokenBucketTest.[engine:junit-jupiter]/[class:com.ledger.api.TokenBucketTest]/[method:a()]</killingTest><description>Replaced double subtraction with addition</description></mutation>
<mutation detected='false' status='SURVIVED' numberOfTestsRun='4'><sourceFile>TokenBucket.java</sourceFile><mutatedClass>com.ledger.api.TokenBucket</mutatedClass><mutatedMethod>tryConsume</mutatedMethod><methodDescription>()V</methodDescription><lineNumber>47</lineNumber><mutator>x.NegateConditionalsMutator</mutator><indexes><index>4</index></indexes><blocks><block>1</block></blocks><killingTest/><description>negated conditional &amp; more</description></mutation>
<mutation detected='false' status='NON_VIABLE' numberOfTestsRun='0'><sourceFile>TokenBucket.java</sourceFile><mutatedClass>com.ledger.api.TokenBucket</mutatedClass><mutatedMethod>x</mutatedMethod><methodDescription>()V</methodDescription><lineNumber>1</lineNumber><mutator>x.M</mutator><indexes><index>5</index></indexes><blocks><block>1</block></blocks><killingTest/><description>nonviable</description></mutation>
</mutations>`;

test('killerClass understands JUnit 5 and JUnit 4 style names', () => {
  assert.equal(killerClass('a.B.[engine:junit-jupiter]/[class:a.B]/[method:m()]'), 'a.B');
  assert.equal(killerClass('a.B.testX(a.B)'), 'a.B');
  assert.equal(killerClass('a.B.testX'), 'a.B');
});

test('parseMutations reads status, location and every killing test class', () => {
  const ms = parseMutations(XML);
  assert.equal(ms.length, 5);
  assert.deepEqual([...ms[0].killers].sort(), ['com.ledger.api.TokenBucketGeneratedTest', 'com.ledger.api.TokenBucketTest']);
  assert.deepEqual([...ms[2].killers], ['com.ledger.api.TokenBucketTest']);
  assert.equal(ms[3].status, 'SURVIVED');
  assert.equal(ms[3].killers.size, 0);
  assert.equal(ms[3].description, 'negated conditional & more');
  assert.equal(ms[0].line, '40');
});

test('analyze attributes kills to generated vs existing tests and scores viable mutants only', () => {
  const generated = ['com.ledger.api.TokenBucketGeneratedTest', 'com.ledger.api.IdleGeneratedTest'];
  const a = analyze(parseMutations(XML), generated);

  assert.equal(a.total, 5);
  assert.equal(a.score, 75); // 3 of 4 viable mutants detected
  assert.equal(a.both, 1);
  assert.equal(a.onlyGenerated, 1);
  assert.equal(a.onlyExisting, 1);

  const gen = a.perGenerated.find((g) => g.cls.endsWith('TokenBucketGeneratedTest'));
  assert.deepEqual({ kills: gen.kills, unique: gen.unique }, { kills: 2, unique: 1 });
  const idle = a.perGenerated.find((g) => g.cls.endsWith('IdleGeneratedTest'));
  assert.deepEqual({ kills: idle.kills, unique: idle.unique }, { kills: 0, unique: 0 });
  assert.equal(a.survivors.length, 1);
});

test('render shows the score, the attribution table, idle tests and survivors', () => {
  const md = renderPit(analyze(parseMutations(XML), ['com.ledger.api.TokenBucketGeneratedTest', 'com.ledger.api.IdleGeneratedTest']));
  assert.match(md, /Mutation score: 75\.0%/);
  assert.match(md, /Killed \*\*only by generated tests\*\*/);
  assert.match(md, /IdleGeneratedTest.* kills nothing/);
  assert.match(md, /Surviving mutants/);
  assert.match(md, /negated conditional & more/);
});

test('render copes with an empty report', () => {
  assert.match(renderPit(analyze([])), /No mutants were generated/);
});
