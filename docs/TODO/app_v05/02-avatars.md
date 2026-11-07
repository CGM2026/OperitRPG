# 02 头像组件

## 设计（已与用户确认）

- 人物头像 = 动物笔触 × 配色；战役头像 = 意象笔触 × 配色。
- 24 种动物，分 6 类（刚猛、机巧、沉静、温良、清高、幽诡），每类 4 种；12 个战役意象。
- 24 组配色取自和田三造《配色事典》（公有领域；数字化数据 MIT，来源 mattdesl/dictionary-of-colour-combinations），14 组浅底、10 组深底；每组有名字与气质说明。不含紫色与墨绿。
- 同一个种子（人物 id 或战役 id）永远得到同一张头像；用户可手动换动物/意象与配色，手动选择优先于种子。
- 模组的人物卡可以给出头像建议（动物 id、配色 id），作为该人物的默认值。

## 资源

`app/src/main/assets/avatars/avatars.json`（由仓库外的头像工具烘焙生成，App 只读取，不在 App 内重新生成笔触）：

```
{ "version": 1, "viewBox": "-50 -50 100 100", "inkScale": 1.18,
  "animals": [ { "id": "wolf", "name": "狼", "category": "fierce", "categoryName": "刚猛", "path": "<SVG path d>" } ],
  "motifs":  [ { "id": "enso", "name": "圆相", "path": "<SVG path d>" } ],
  "pairs":   [ { "id": "p01", "name": "晴川", "mood": "开朗正直，带点风尘", "tone": "light", "bg": "#A7D4E4", "line": "#82241F", "source": "Wada #16", "contrast": 5.93 } ] }
```

绘制方式：以中心为原点，先画半径 50 的圆（填 `bg`），再把 `path` 按 `inkScale` 放大后填 `line`。path 全部是闭合填充轮廓（笔触已烘焙成有粗细变化的形状），不是描边。

## 代码

包 `com.ai.assistance.operit.rpg.ui.avatar`：

1. `AvatarCatalog`：从 assets 读取并用 kotlinx.serialization 严格解析（未知字段报错）。加载时校验：id 唯一、动物 24 / 意象 12 / 配色 24、颜色是 `#RRGGBB`、path 非空。
2. `AvatarSpec(kind: AvatarKind, glyphId: String, pairId: String)`，`AvatarKind` = `CHARACTER` | `CAMPAIGN`。
3. `AvatarCatalog.pick(kind, seed: String): AvatarSpec`：对 seed 的 UTF-8 字节做 SHA-256，取前 8 字节为无符号长整数 h；glyph 下标 = h mod 数量，pair 下标 = (h ushr 32) mod 24。纯函数，相同输入永远相同输出。
4. `@Composable fun Avatar(spec: AvatarSpec, size: Dp, modifier: Modifier = Modifier)`：用 `Canvas` 绘制；path 用 `androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()` 解析，按 spec 缓存（`remember`）；变换为平移到中心、缩放 `size/100 * inkScale`。
5. 本步先把它接到现有首页列表：每个战役条目左侧显示 `pick(CAMPAIGN, campaignId)` 的头像（44dp）。手动更换与持久化在 03 步做。

## 测试（JVM 单元测试，`app/src/test`）

- 读取 `src/main/assets/avatars/avatars.json` 文件解析成功且计数正确。
- `pick` 对同一 seed 两次结果相同；对 1000 个不同 seed，24 种配色每种都至少出现一次。

## 验收

CI 绿（rpg-agent 测试、App 单元测试、`:app:compileDebugKotlin`）。
