import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { lintFile } from '../scripts/check-doc.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = (name) => path.join(here, 'fixtures', name);
const rulesOf = (issues) => issues.map((i) => i.rule);

test('负控：通用 profile 下合格文档零问题', () => {
  const issues = lintFile(fixture('good.md'), { profile: 'generic' });
  assert.deepEqual(issues, []);
});

test('正控：通用 profile 抓到结构问题', () => {
  const rules = rulesOf(lintFile(fixture('bad-generic.md'), { profile: 'generic' }));
  for (const rule of ['doc/heading-skip', 'doc/empty-section', 'doc/table-columns', 'doc/table-pipe-in-code', 'doc/code-fence-lang', 'doc/link-missing', 'doc/placeholder']) {
    assert.ok(rules.includes(rule), `缺少 ${rule}，实际 ${rules.join(', ')}`);
  }
});

test('负控：语雀专有问题在通用 profile 不误报', () => {
  const rules = rulesOf(lintFile(fixture('bad-yuque.md'), { profile: 'generic' }));
  assert.ok(!rules.some((r) => r.startsWith('math/')));
  assert.ok(!rules.includes('html/details'));
  assert.ok(!rules.some((r) => r.startsWith('mermaid/')));
});

test('正控：语雀 profile 抓到渲染红线', () => {
  const rules = rulesOf(lintFile(fixture('bad-yuque.md'), { profile: 'yuque' }));
  for (const rule of ['math/escaped-delimiter', 'html/details', 'html/hr-glued-yuque', 'math/dollar-balance', 'mermaid/subgraph', 'mermaid/bidi-label']) {
    assert.ok(rules.includes(rule), `缺少 ${rule}，实际 ${rules.join(', ')}`);
  }
});

const CLI = path.join(here, '..', 'scripts', 'check-doc.mjs');
const run = (args) => spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8' });

test('CLI：合格文档退出码 0', () => {
  assert.equal(run(['--path', fixture('good.md')]).status, 0);
});

test('CLI：问题文档退出码 4', () => {
  assert.equal(run(['--path', fixture('bad-generic.md')]).status, 4);
});

test('CLI：路径不存在退出码 2', () => {
  assert.equal(run(['--path', fixture('nope.md')]).status, 2);
});

test('CLI：--help 退出码 0', () => {
  const r = run(['--help']);
  assert.equal(r.status, 0);
  assert.match(r.stdout, /profile/);
});

test('CLI：JSON 报告可解析且计数一致', () => {
  const out = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'doc-write-')), 'report.json');
  const r = run(['--path', fixture('bad-generic.md'), '--json', out, '--quiet']);
  assert.equal(r.status, 4);
  const data = JSON.parse(fs.readFileSync(out, 'utf8'));
  assert.equal(data.profile, 'generic');
  assert.ok(data.errorCount > 0);
  assert.equal(data.issues.length, data.errorCount + data.warnCount);
});
