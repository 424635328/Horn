#!/usr/bin/env node
// 文档结构检查器：默认按通用 Markdown 规则，--profile yuque 追加语雀渲染红线。
// 只读文件，不改内容；退出码 0 通过 / 2 参数 / 3 内部错误 / 4 校验失败。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const EXIT = { OK: 0, USAGE: 2, INTERNAL: 3, LINT: 4 };
const VERSION = '1.0.0';

const RULES = {
  'doc/single-h1': 'error',
  'doc/heading-skip': 'warn',
  'doc/empty-section': 'warn',
  'doc/code-fence-lang': 'warn',
  'doc/code-fence-open': 'error',
  'doc/table-columns': 'error',
  'doc/table-pipe-in-code': 'error',
  'doc/link-missing': 'error',
  'doc/placeholder': 'warn',
  'doc/frontmatter': 'warn',
  'html/hr-glued': 'warn',
  'math/escaped-delimiter': 'error',
  'math/dollar-balance': 'error',
  'html/details': 'error',
  'html/block': 'warn',
  'html/hr-glued-yuque': 'error',
  'mermaid/subgraph': 'error',
  'mermaid/bidi-label': 'error',
  'mermaid/node-quote': 'warn',
  'mermaid/subgraph-open': 'error',
};

function add(list, file, line, rule, message, severity = RULES[rule] ?? 'warn') {
  list.push({ file, line, rule, severity, message });
}

function stripInlineCode(line) {
  return line.replace(/`[^`]*`/g, '');
}

function isExternal(target) {
  return /^(https?:|mailto:|#|tel:|data:)/i.test(target);
}

function columnCount(row) {
  // 行内代码里的竖线按内容处理，另用 doc/table-pipe-in-code 单独报
  const s = row.replace(/`[^`]*`/g, (span) => span.replace(/\|/g, 'X')).replace(/\\\|/g, '');
  let pipes = (s.match(/\|/g) ?? []).length;
  if (s.trimStart().startsWith('|')) pipes -= 1;
  if (s.trimEnd().endsWith('|')) pipes -= 1;
  return pipes + 1;
}

function hasPipeInCode(row) {
  return (row.match(/`[^`]*`/g) ?? []).some((span) => /(?<!\\)\|/.test(span));
}

function lintMermaid(lines, startLine, issues, file) {
  const content = lines.filter((l) => l.trim());
  if (!content.length) {
    add(issues, file, startLine, 'mermaid/subgraph', 'Mermaid 代码块为空');
    return;
  }
  const first = content[0].trim();
  if (!/^(flowchart|graph|sequenceDiagram|classDiagram|stateDiagram-v2|stateDiagram|erDiagram|gantt|pie|journey|gitGraph|mindmap|timeline)\b/.test(first)) {
    add(issues, file, startLine, 'mermaid/subgraph', `首行不是合法图类型声明：${first}`);
    return;
  }
  const isFlow = /^(flowchart|graph)\b/.test(first);
  let depth = 0;
  lines.forEach((raw, i) => {
    const t = raw.trim();
    if (!t) return;
    const lineNo = startLine + i;
    if (/(<-->|<==>)\s*\|/.test(t)) {
      add(issues, file, lineNo, 'mermaid/bidi-label', '双向箭头不能带标签：改用 ---|"文字"| 或拆成两条单向箭头');
    }
    if (!isFlow) return;
    if (/^subgraph\b/.test(t)) {
      depth += 1;
      if (!/^subgraph\s+[A-Za-z_][A-Za-z0-9_]*\s*(\[".*"\])?\s*$/.test(t)) {
        add(issues, file, lineNo, 'mermaid/subgraph', '子图需英文 ID + 引号标题，例如 subgraph SG1["输入层"]');
      }
    } else if (/^end\s*$/.test(t)) {
      depth = Math.max(0, depth - 1);
    } else if (/\[[^"\]]*[()][^"\]]*\]/.test(t)) {
      add(issues, file, lineNo, 'mermaid/node-quote', '节点文本含括号但未加引号，建议 A["文本(说明)"]');
    }
  });
  if (isFlow && depth > 0) {
    add(issues, file, startLine, 'mermaid/subgraph-open', '有 subgraph 没有对应的 end');
  }
}

export function lintFile(file, { profile = 'generic' } = {}) {
  const issues = [];
  const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
  const lines = text.split(/\r?\n/);
  const yuque = profile === 'yuque';

  let inFence = false;
  let fenceChar = '';
  let fenceLen = 0;
  let fenceLang = '';
  let fenceStart = 0;
  let mermaidBuf = [];
  let inFrontmatter = false;
  let h1 = 0;
  let lastHeading = 0;
  let headingNeedsContent = false;
  let dollarBlocks = 0;

  if (lines[0]?.trim() === '---') {
    inFrontmatter = true;
    add(issues, file, 1, 'doc/frontmatter', '正文顶部不要写 frontmatter：多数平台会按分割线或表格渲染');
  }

  for (let i = 0; i < lines.length; i += 1) {
    const raw = lines[i];
    const trim = raw.trim();
    const lineNo = i + 1;

    if (inFrontmatter) {
      if (trim === '---') inFrontmatter = false;
      continue;
    }

    const fenceMatch = /^(`{3,}|~{3,})/.exec(trim);
    if (fenceMatch) {
      const marker = fenceMatch[0];
      const char = marker[0];
      const len = marker.length;
      if (!inFence) {
        inFence = true;
        fenceChar = char;
        fenceLen = len;
        fenceLang = trim.slice(len).trim();
        fenceStart = lineNo;
        mermaidBuf = [];
        headingNeedsContent = false;
        if (!fenceLang) {
          add(issues, file, lineNo, 'doc/code-fence-lang', '代码块缺少语言标注');
        }
      } else if (char === fenceChar && len >= fenceLen) {
        inFence = false;
        if (/^mermaid/i.test(fenceLang) && yuque) {
          lintMermaid(mermaidBuf, fenceStart + 1, issues, file);
        }
        fenceLang = '';
        mermaidBuf = [];
      } else if (/^mermaid/i.test(fenceLang)) {
        mermaidBuf.push(raw);
      }
      continue;
    }
    if (inFence) {
      if (/^mermaid/i.test(fenceLang)) mermaidBuf.push(raw);
      continue;
    }

    // 表格：表头 + 分隔行 + 数据行
    const nextTrim = lines[i + 1]?.trim() ?? '';
    const isSeparator = /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(nextTrim);
    if (isSeparator && trim.includes('|')) {
      const headerCols = columnCount(trim);
      if (hasPipeInCode(trim)) {
        add(issues, file, lineNo, 'doc/table-pipe-in-code', '表头单元格的行内代码里有未转义竖线，会截断表格；应写成 \\|');
      }
      if (columnCount(nextTrim) !== headerCols) {
        add(issues, file, lineNo, 'doc/table-columns', '表头与分隔行列数不一致，表格会错位');
      }
      let j = i + 2;
      while (j < lines.length) {
        const row = lines[j].trim();
        if (!row || !row.includes('|')) break;
        if (hasPipeInCode(row)) {
          add(issues, file, j + 1, 'doc/table-pipe-in-code', '单元格的行内代码里有未转义竖线，会截断表格；应写成 \\|');
        }
        if (columnCount(row) !== headerCols) {
          add(issues, file, j + 1, 'doc/table-columns', `该行 ${columnCount(row)} 列，表头 ${headerCols} 列`);
        }
        j += 1;
      }
      i = j - 1;
      headingNeedsContent = false;
      continue;
    }

    const plain = stripInlineCode(raw);

    if (yuque) {
      if (/(?<!\\)\\[(\[]/.test(plain) || /(?<!\\)\\[)\]]/.test(plain)) {
        add(issues, file, lineNo, 'math/escaped-delimiter', '语雀公式只认 $...$ 与 $$...$$，不认 \\( \\) \\[ \\]');
      }
      dollarBlocks += (plain.match(/\$\$/g) ?? []).length;
      if (/<\s*\/?\s*(details|summary)\b/i.test(plain)) {
        add(issues, file, lineNo, 'html/details', '语雀不解析 details 折叠块，块内 Markdown 会失效');
      }
      if (/^<\s*\/?\s*(div|p|table|ul|ol|pre|blockquote|h[1-6])\b/i.test(plain)) {
        add(issues, file, lineNo, 'html/block', '块级 HTML 内的 Markdown 不解析，内容会被压成一行');
      }
    }

    if (/^([-*_])(\s*\1){2,}$/.test(trim)) {
      const prevBlank = i === 0 || !lines[i - 1].trim();
      const nextBlank = i + 1 >= lines.length || !lines[i + 1].trim();
      if (!(prevBlank && nextBlank)) {
        add(
          issues, file, lineNo,
          yuque ? 'html/hr-glued-yuque' : 'html/hr-glued',
          '分割线前后必须各留一个空行，否则会与上下文粘连',
        );
      }
    }

    const heading = /^(#{1,6})\s+(\S.*?)\s*$/.exec(trim);
    if (heading) {
      const level = heading[1].length;
      if (level === 1) h1 += 1;
      if (lastHeading > 0 && level > lastHeading + 1) {
        add(issues, file, lineNo, 'doc/heading-skip', `标题从 H${lastHeading} 跳到 H${level}`);
      }
      if (headingNeedsContent) {
        add(issues, file, lineNo, 'doc/empty-section', '上一级标题下没有内容就出现新标题');
      }
      lastHeading = level;
      headingNeedsContent = true;
      continue;
    }

    for (const m of plain.matchAll(/!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
      const target = m[1];
      if (isExternal(target)) continue;
      const clean = decodeURIComponent(target.split('#')[0].split('?')[0]);
      if (!clean) continue;
      if (!fs.existsSync(path.resolve(path.dirname(file), clean))) {
        add(issues, file, lineNo, 'doc/link-missing', `相对链接指向不存在的文件：${target}`);
      }
    }

    if (/【[^】\n]*】|\b(TODO|TBD|FIXME)\b|待补充|xxx 待定/.test(plain)) {
      add(issues, file, lineNo, 'doc/placeholder', '仍有占位符未替换');
    }

    if (trim) headingNeedsContent = false;
  }

  if (inFence) {
    add(issues, file, fenceStart, 'doc/code-fence-open', '代码块没有闭合');
  }
  if (yuque && dollarBlocks % 2 !== 0) {
    add(issues, file, 0, 'math/dollar-balance', `全文 $$ 出现 ${dollarBlocks} 次，未成对`);
  }
  if (h1 === 0) add(issues, file, 0, 'doc/single-h1', '文档没有一级标题');
  if (h1 > 1) add(issues, file, 0, 'doc/single-h1', `文档有 ${h1} 个一级标题，应只保留一个`);

  return issues;
}

function collectFiles(targets) {
  const out = [];
  for (const target of targets) {
    const abs = path.resolve(target);
    if (!fs.existsSync(abs)) throw new Error(`路径不存在：${target}`);
    const stat = fs.statSync(abs);
    if (stat.isDirectory()) {
      for (const entry of fs.readdirSync(abs, { withFileTypes: true })) {
        if (entry.isFile() && entry.name.toLowerCase().endsWith('.md')) out.push(path.join(abs, entry.name));
      }
    } else {
      out.push(abs);
    }
  }
  return out;
}

function parseArgs(argv) {
  const opts = { paths: [], profile: 'generic', json: null, strict: false, quiet: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--path') opts.paths.push(argv[++i]);
    else if (a === '--profile') opts.profile = argv[++i];
    else if (a === '--json') opts.json = argv[++i];
    else if (a === '--strict') opts.strict = true;
    else if (a === '--quiet') opts.quiet = true;
    else if (a === '--help' || a === '-h') opts.help = true;
    else opts.paths.push(a);
  }
  return opts;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help || !opts.paths.length) {
    console.log(`check-doc —— 文档结构检查

用法：node check-doc.mjs --path <文件或目录> [--path …] [--profile generic|yuque]
                        [--json <报告路径>] [--strict] [--quiet]

  generic  通用 Markdown：标题、表格、代码块、相对链接、占位符
  yuque    追加语雀红线：公式分隔符、details、分割线空行、Mermaid 子图/双向箭头

退出码：0 通过 / 2 参数 / 3 内部错误 / 4 校验失败`);
    return opts.help ? EXIT.OK : EXIT.USAGE;
  }
  if (!['generic', 'yuque'].includes(opts.profile)) {
    console.error(`未知 profile：${opts.profile}`);
    return EXIT.USAGE;
  }

  let files;
  try {
    files = collectFiles(opts.paths);
  } catch (err) {
    console.error(err.message);
    return EXIT.USAGE;
  }
  if (!files.length) {
    console.error('没有找到 .md 文件');
    return EXIT.USAGE;
  }

  const issues = [];
  for (const file of files) {
    try {
      issues.push(...lintFile(file, { profile: opts.profile }));
    } catch (err) {
      console.error(`[error] ${file}: ${err.message}`);
      return EXIT.INTERNAL;
    }
  }

  const errors = issues.filter((i) => i.severity === 'error');
  const warns = issues.filter((i) => i.severity !== 'error');

  if (!opts.quiet) {
    for (const issue of [...errors, ...warns]) {
      const tag = issue.severity === 'error' ? 'ERROR' : 'WARN ';
      console.log(`${tag} ${issue.file}${issue.line ? `:${issue.line}` : ''}  [${issue.rule}] ${issue.message}`);
    }
    console.log(`\n检查 ${files.length} 个文件：error ${errors.length}，warn ${warns.length}（profile=${opts.profile}）`);
  }

  if (opts.json) {
    const payload = {
      tool: 'check-doc', version: VERSION, profile: opts.profile,
      generatedAt: new Date().toISOString(), files, errorCount: errors.length, warnCount: warns.length, issues,
    };
    fs.mkdirSync(path.dirname(path.resolve(opts.json)), { recursive: true });
    const tmp = `${opts.json}.partial`;
    fs.writeFileSync(tmp, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
    fs.renameSync(tmp, opts.json);
  }

  if (errors.length) return EXIT.LINT;
  if (opts.strict && warns.length) return EXIT.LINT;
  return EXIT.OK;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) process.exit(main());