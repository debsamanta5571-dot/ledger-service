import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseSourcePath,
  testPathFor,
  testClassFor,
  skipReason,
  countBodies,
  scanForbidden,
  extractJava,
  validateGenerated,
  buildPrompt,
} from './lib.mjs';

const PATH = 'src/main/java/com/ledger/api/TokenBucket.java';

test('parseSourcePath extracts package and class', () => {
  assert.deepEqual(parseSourcePath(PATH), { pkg: 'com.ledger.api', name: 'TokenBucket', fqcn: 'com.ledger.api.TokenBucket' });
  assert.equal(parseSourcePath('README.md'), null);
  assert.equal(parseSourcePath('src/test/java/com/ledger/FooTest.java'), null);
  assert.equal(parseSourcePath('src\\main\\java\\com\\x\\Y.java').fqcn, 'com.x.Y');
});

test('test path and class name follow the naming convention', () => {
  assert.equal(testPathFor(PATH), 'src/test/java/com/ledger/api/TokenBucketGeneratedTest.java');
  assert.equal(testClassFor(PATH), 'TokenBucketGeneratedTest');
  assert.throws(() => testPathFor('foo.txt'));
});

test('skipReason skips entry points, exceptions, properties and logic-free files', () => {
  const logic = 'public class A {\n int f(int x) { return x; }\n int g(int y) { return y; }\n}';
  assert.equal(skipReason('src/main/java/com/x/A.java', logic), null);
  assert.match(skipReason('src/main/java/com/x/LedgerApplication.java', logic), /entry point/);
  assert.match(skipReason('src/main/java/com/x/BoomException.java', logic), /exception/);
  assert.match(skipReason('src/main/java/com/x/AuthProperties.java', logic), /properties/);
  assert.match(skipReason('src/main/java/com/x/Dto.java', 'public record Dto(int a) {\n}'), /no logic/);
  assert.match(skipReason('pom.xml', logic), /not a main/);
});

test('countBodies ignores comments', () => {
  assert.equal(countBodies('// foo() {\n/* bar() { */\nint a() { return 1; }'), 1);
});

test('scanForbidden flags dangerous constructs and allows plain unit tests', () => {
  assert.deepEqual(scanForbidden('assertThat(x).isEqualTo(1);'), []);
  assert.ok(scanForbidden('Runtime.getRuntime().exec("x")').includes('Runtime.getRuntime'));
  assert.ok(scanForbidden('new ProcessBuilder("sh")').includes('ProcessBuilder'));
  assert.ok(scanForbidden('System.getenv("SECRET")').length > 0);
  assert.ok(scanForbidden('java.net.http.HttpClient.newHttpClient()').length > 0);
  assert.ok(scanForbidden('@SpringBootTest class X {}').length > 0);
  assert.ok(scanForbidden('Thread.sleep(10)').length > 0);
});

test('extractJava takes the fenced block or raw Java, else null', () => {
  assert.equal(extractJava('Here you go:\n```java\npackage a;\nclass X {}\n```\nDone'), 'package a;\nclass X {}\n');
  assert.equal(extractJava('```\nclass Y {}\n```'), 'class Y {}\n');
  assert.equal(extractJava('package a;\nclass Z {}'), 'package a;\nclass Z {}\n');
  assert.equal(extractJava('sorry, I cannot do that'), null);
});

const GOOD = `package com.ledger.api;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class TokenBucketGeneratedTest {
  @Test void works() { assertThat(1).isEqualTo(1); }
}`;

test('validateGenerated accepts a well-formed test', () => {
  assert.deepEqual(validateGenerated(GOOD, PATH), []);
});

test('validateGenerated rejects wrong package, wrong class, no tests, no assertions and forbidden code', () => {
  assert.ok(validateGenerated(GOOD.replace('com.ledger.api', 'com.other'), PATH).some((p) => /package/.test(p)));
  assert.ok(validateGenerated(GOOD.replace('TokenBucketGeneratedTest', 'Nope'), PATH).some((p) => /class/.test(p)));
  assert.ok(validateGenerated(GOOD.replace('@Test', ''), PATH).some((p) => /@Test/.test(p)));
  assert.ok(validateGenerated(GOOD.replace('assertThat(1).isEqualTo(1);', ''), PATH).some((p) => /assertions/.test(p)));
  assert.ok(validateGenerated(GOOD + '\nRuntime.getRuntime();', PATH).some((p) => /forbidden/.test(p)));
  assert.deepEqual(validateGenerated(null, PATH), ['no Java code found in the model reply']);
});

test('buildPrompt wraps source and context as data and names the target class', () => {
  const prompt = buildPrompt({
    path: PATH,
    source: 'class TokenBucket {}',
    context: [{ path: 'src/main/java/com/ledger/api/ApiKey.java', text: 'record ApiKey() {}' }],
  });
  assert.match(prompt, /TokenBucketGeneratedTest/);
  assert.match(prompt, /<source path="src\/main\/java\/com\/ledger\/api\/TokenBucket.java">/);
  assert.match(prompt, /<context path="src\/main\/java\/com\/ledger\/api\/ApiKey.java">/);
  assert.match(prompt, /package com\.ledger\.api/);
});
