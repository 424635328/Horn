# 开屏参考图素材管线（`tools/startup-hero`）

把开屏的参考图（`feature/horn/src/main/res/drawable-nodpi/startup_hero.png`）离线转成
**线稿折线素材**，供开屏动画的「线稿飞散 → 逐段归位 → 上色回原图」使用。

## 为什么在构建期做

边缘检测（Sobel + 非极大值抑制 + 边缘行走）属于 skill 定义的"重活"：
**换图时跑一次，绝不进每帧路径**。放到运行时还会带来首帧解码与堆分配，
与「先预热再开演、不卡首帧」直接冲突。因此这里离线产出二进制素材，App 侧只做一次读取与切分。

算法来自仓库内 vendored skill（**只读引用，不修改**）：

```
.agents/skills/startup-animation/scripts/vectorize.mjs
```

## 用法

```bash
node tools/startup-hero/extract-contours.mjs          # 重新生成素材
node tools/startup-hero/extract-contours.mjs --check  # 校验素材是否与参考图一致（CI/预检用）
```

退出码：`0` 成功或一致 / `2` 输入问题（参考图缺失、格式不支持）/ `3` `--check` 发现素材过期 /
`4` 自检失败（同输入两次产出不同字节）。

脚本内部会跑两次提取并逐字节比对 —— 这是 skill 的确定性要求，避免"同一张图每次渲染都不一样"。

## 产物

`feature/horn/src/main/assets/startup/hero_contours.bin`（小端）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `magic` | 4B ASCII | `HRNC` |
| `version` | u16 | 当前为 `1` |
| `reserved` | u16 | 0 |
| `imageW` / `imageH` | u16 | 参考图源尺寸（当前 658 × 877） |
| `pathCount` | u32 | 折线条数 |
| 每条折线 | `pointCount u16` + `pointCount × (x u16, y u16)` | 坐标归一化到 `0..1`，`u16/65535` |

当前产出：**1000 条折线 / 24 108 点 / 96 KB**（sha256 前 16 位 `c5da357f4b876a75`）。

## 换图流程

1. 覆盖 `startup_hero.png`（保持源尺寸或同步改下文的比例说明）。
2. `node tools/startup-hero/extract-contours.mjs` 重新生成素材。
3. 跑 `node tools/startup-hero/extract-contours.mjs --check` 确认一致。
4. 重录开屏定格帧（Linux，见 `.github/workflows/update-screenshots.yml`）并更新
   `docs/assets/startup-film/*.png`。

## 许可与出处

`startup_hero.png` 是外部提供的参考图（角色插画），**版权归原权利方**。
本仓库仅把它作为开屏素材示例使用；对外分发/商用前必须自行取得授权，
不得把该文件当作可再分发的自由素材。详见 `docs/spec/03` §11.1.4。
