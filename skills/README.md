# 随仓库分发的 Agent Skills（`skills/`）

- 定位：**开发期辅助资产**，不参与 App 构建、不进入 APK、不被 Gradle 模块图感知。
- 收录原则：只收录**确实在本项目开发中实际加载并产出了仓库内产物**的 skill；没用到、或只尝试但没有产出的不收录。
- 快照来源：仓库维护者的本地 agent skill 库（`<HOME>/.agents/skills`），快照日期 **2026-11-23**。
- 许可与第三方：内容为自建方法论文档；`startup-animation` 明确声明其方法论"只学方法、不抄代码"。
  若后续引入任何第三方代码，MUST 在本文件的"许可"一节登记许可证与出处。

## 1. 收录清单与证据

| Skill | 用途 | 本项目的产物证据 | 运行依赖 |
|---|---|---|---|
| [`doc-write`](doc-write/SKILL.md) | 交付型文档写作流程 + `check-doc.mjs` 结构校验器（标题层级、表格列数、代码块语言、相对链接、占位符、语雀红线） | [docs/spec/01](../docs/spec/01-system-architecture.md)、[02](../docs/spec/02-audio-dsp-engine.md)、[03](../docs/spec/03-ux-interaction-and-haptics.md)、[06](../docs/spec/06-key-technologies.md) 等规约的写作与自检流程；[交付报告](../docs/reports/v3.6-stereo-engine-delivery.md) | Node ≥ 20 |
| [`research`](research/SKILL.md) | 派后台 agent 只查一手来源（官方文档/源码/标准），把结论连同来源写成 Markdown | [research/00](../docs/spec/research/00-stereo-pipeline-contract.md)（冻结契约）与 [01](../docs/spec/research/01-horn-physical-modeling.md)–[03](../docs/spec/research/03-psychoacoustics-transducer-adaptation.md) 三份带 DOI 逐条核实的规约 | 无（纯流程） |
| [`startup-animation`](startup-animation/SKILL.md) | 单时钟 f(t) 启动动画方法论：时间轴分镜、缓动原语、线稿聚合、预热交棒、帧预算与降级验收 | [StartupWake.kt](../core/designsystem/src/main/java/com/industrial/horn/ui/theme/StartupWake.kt)、[StartupOverlay.kt](../feature/horn/src/main/java/com/industrial/horn/ui/StartupOverlay.kt)、[spec 03 §11](../docs/spec/03-ux-interaction-and-haptics.md)、[ADR-0010](../docs/spec/adr/ADR-0010-startup-optical-wake-animation.md)、`StartupOverlaySnapshotTest` 6 帧基准 | Node ≥ 20；`selftest.mjs` 另需本机 Chromium/Chrome |

**明确不收录**：`archify`。v3.9 曾派 3 个子会话尝试产出架构图，全部未产出任何文件；且它是第三方插件内置 skill
（`@tt-a1i/archify-dsh`），代码与许可证不随本仓库分发。如未来需要架构图，应作为独立依赖引入并单独登记许可。

## 2. 目录约定

```
skills/
├── README.md                 # 本文件：收录依据、脱敏记录、维护规则
├── manifest.sha256           # 完整性清单（sha256sum 格式），由 checker 校验
├── <skill>/
│   ├── SKILL.md              # 必需：YAML frontmatter(name/description) + 正文
│   ├── references/*.md       # 按需加载的细则
│   ├── scripts/*.mjs         # 可执行辅助脚本（Node）
│   └── tests/**              # 脚本自测与 fixture
```

命名必须与 frontmatter 的 `name` 一致（checker 强制）；新增 skill 必须能在 §1 表格里写出本项目的产物证据。

## 3. 脱敏记录（本快照相对本地库的改动）

| # | 文件 | 改动 | 原因 |
|---|---|---|---|
| 1 | `doc-write/SKILL.md` | `"$HOME/.agents/skills/doc-write/scripts/check-doc.mjs"` → `skills/doc-write/scripts/check-doc.mjs`；删除 Windows PowerShell 的 `$env:USERPROFILE\...` 说明，改为"路径相对仓库根目录解析" | 去掉宿主绝对路径与个人目录 |
| 2 | `doc-write/SKILL.md` | 交叉引用 `yuque-learning-doc`、`docx` 改为"未随本仓库分发" | 避免悬空依赖（本仓库不含这两个 skill） |
| 3 | `startup-animation/SKILL.md` | 第三方实现的项目名 `codex-startup-animation-windows` → "一个商业启动动画实现" | 去掉无关第三方项目标识 |
| 4 | 全部文本 | CRLF → LF、去 BOM；脚本逻辑零改动 | 仓库统一换行与编码 |
| 5 | `research/SKILL.md` | 无内容改动 | — |
| 6 | 未收录 | `research/agents/openai.yaml`（宿主 agent 清单） | 与 skill 内容无关，属宿主配置 |

**未出现**：凭据、Cookie、Token、私钥、个人邮箱、真实姓名、私有仓库地址、机器名。

## 4. 验证（本地与 CI 同一条命令）

```bash
python .github/scripts/check_skills.py --self-test   # 检查器自身的正控/负控
python .github/scripts/check_skills.py               # 结构 + 脱敏 + 链接 + manifest
node --test skills/doc-write/tests/check-doc.test.mjs
node skills/startup-animation/scripts/selftest.mjs   # 需要本机浏览器，CI 不执行
```

退出码：`0` 通过 / `2` 结构或脱敏或链接失败 / `3` manifest 不一致 / `4` 自检失败。
CI 在 `Incremental checks` 中执行前两条；`pre-push` 钩子同样执行。

## 5. 维护规则

1. **内容只读**：修正方法论文本请回上游 skill 库修改后重新快照，不在本仓库就地重写，避免双份事实源。
   唯一的例外是脱敏与仓库相对路径修正。
2. **每次快照**必须同时更新 `manifest.sha256` 与本文件的快照日期、脱敏记录。
3. **新增 skill** 必须能指出本项目中的具体产物（文件 / PR / ADR），否则不收录。
4. `skills/**` 被排除在 spotless `misc` 之外：vendored 内容不被格式化器改写，但仍受本 checker 的
   链接、脱敏与 frontmatter 约束。
5. 任何 skill 都 MUST NOT 引入网络运行时依赖或读取受保护数据；脚本必须在退出码里区分失败原因。
