# 模组包格式（严格核对版）

本文件逐项说明模组包的数据结构、字段、默认值、校验和错误码。所有结论来自 `Models.kt`、`D20Models.kt`、`ModuleZipImporter.kt`、`StrictJson`、`RuleAdapters.kt`、`GenericD100.kt`、`WuxiaD20.kt`、`NarrativeEffects.kt`、`Scheduler.kt`、`Chapters.kt` 与 `DiceExpression.kt`。没有写进代码的限制，本文件不会替它增加。

> 模组包只使用 `ModuleManifest`、`ModuleWorld`、`ModuleRules` 及其嵌套类型。`ValidatedModule`、`WorldState`、`Campaign`、`WorldEvent`、`ScheduledFiring`、`D20State`、`D20Action` 等是引擎运行期类型，作者不写入模组包；附录列出它们的字段，便于读错误信息时对照。

## 1. 严格 JSON 与文件编码

所有 JSON 由 `StrictJson` 解析，配置为：

```kotlin
Json { ignoreUnknownKeys=false; isLenient=false; coerceInputValues=false }
```

因此：

- 只接受标准 JSON 对象；不能有注释、尾逗号、单引号、未加引号的 key、`NaN`/`Infinity`。
- **未知字段一律拒绝**（包括嵌套对象里的未知字段）。
- **重复 key 一律拒绝**：`DUPLICATE_JSON_KEY`。检测是逐个对象做的，嵌套重复也能发现。
- 类型必须精确：字符串不能写成数字，整数不能写成带小数的数字，枚举必须大小写完全一致。
- 非空字段不能传 `null`；可空字段可以省略（如果声明了默认值）或显式传 `null`；**可空但没有默认值的字段必须出现**（可写 `null`）。
- JSON 嵌套容器深度上限 64；超过报 `JSON_DEPTH_LIMIT`。
- 每个被 `StrictJson.decode` 的 JSON 文件最大 **262144 字节**（UTF-8 字节数，不是字符数）；超过报 `JSON_LIMIT`。适用于 `module/manifest.json`、`module/world.json`、`module/rules.json`。
- 文本必须是合法 UTF-8，解码器使用 `CodingErrorAction.REPORT`；非法字节直接失败（无自定义错误码）。建议无 BOM。
- 数字必须落在目标类型范围内（`Int` / `Long`），否则序列化失败。
- 作者可以选择缩进或紧凑格式；**哈希按实际字节计算**，所以同一份数据换格式就会换哈希。

## 2. ZIP 结构与 manifest

### 2.1 允许的路径

导入器先扫描整个 ZIP，再逐条目检查。允许的路径只有：

```text
module/manifest.json
module/world.json
module/rules.json
module/sources/[A-Za-z0-9_-]+\.(md|txt)
```

拒绝规则（`ZIP_PATH_REJECTED`）：

- 以 `/` 开头的绝对路径；
- 包含 `\`；
- 包含 `:`；
- 按 `/` 切分后有空段、`.` 或 `..`；
- 目录条目（`isDirectory` 为真）；
- 不在上述白名单中的任何文件。

此外：

| 检查 | 限制 / 错误码 |
| --- | --- |
| 整个 ZIP 字节数 | ≤ 20 MiB（`20*1024*1024`）；超过 `ZIP_SIZE_LIMIT` |
| 条目数 | ≤ 2000；超过 `ZIP_ENTRY_LIMIT` |
| 单条目解压后大小 | ≤ 5 MiB；超过 `ZIP_SIZE_LIMIT` |
| 所有条目解压后总大小 | ≤ 50 MiB；超过 `ZIP_EXPANDED_LIMIT` |
| 重复路径 | 拒绝，`ZIP_DUPLICATE_PATH` |
| 符号链接 | 拒绝，`ZIP_SYMLINK_REJECTED`（检查 Unix 外部属性的文件类型位） |
| CRC | `ZipInputStream.closeEntry()` 会按实际解压字节校验；不匹配抛 `ZipException`（无自定义错误码） |

ZIP 内必须有且只需有 `module/manifest.json`、`module/world.json`、`module/rules.json`；缺任一报 `MODULE_FILE_MISSING`。source 文件可以没有（`rules.evidence` 为空时），但需要证据的行动必须有对应 source。

### 2.2 `ModuleManifest` 字段

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `formatVersion` | Int | 必填 | 必须为 `1`；否则 `UNSUPPORTED_MODULE_VERSION` |
| `moduleId` | String | 必填 | 必须匹配 `[a-z][a-z0-9-]{2,63}`（小写字母开头，3–64 字符，只含小写字母/数字/连字符）；否则 `INVALID_MODULE_ID` |
| `version` | String | 必填 | 必须匹配 `[0-9]+\.[0-9]+\.[0-9]+`；否则 `INVALID_MODULE_ID` |
| `title` | String | 必填 | 包标题；代码不做长度/非空校验 |
| `author` | String | 必填 | 作者；代码不做长度/非空校验 |
| `description` | String | 必填 | 简介；代码不做长度/非空校验；预览会显示，不能泄密 |
| `engineApiVersion` | Int | 必填 | 必须为 `1`（`AGENT_API_VERSION`）；否则 `UNSUPPORTED_MODULE_VERSION` |
| `rulesetId` | String | 必填 | 必须等于 `rules.adapterId`；否则 `RULE_VERSION_MISMATCH` |
| `rulesetVersion` | String | 必填 | 必须等于 `rules.adapterVersion`；否则 `RULE_VERSION_MISMATCH` |
| `files` | List<FileDigest> | 必填 | 除 manifest 外所有 ZIP 条目的清单 |

### 2.3 `FileDigest` 字段

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `path` | String | 必填 | ZIP 内精确路径；`manifest.files` 中路径不能重复（无自定义错误码） |
| `size` | Long | 必填 | 该文件实际字节数；不一致报 `MODULE_HASH_MISMATCH` |
| `sha256` | String | 必填 | 该文件实际字节的 SHA-256，小写十六进制；不一致报 `MODULE_HASH_MISMATCH`。比较是字符串精确比较，因此必须小写 |

`manifest.files` 的路径集合必须**正好等于** ZIP 中除 `module/manifest.json` 以外的全部路径；否则 `MANIFEST_FILE_MISMATCH`。manifest 自身不能列进 `files`，也不能有未列出的额外文件。

### 2.4 哈希计算

对每个非 manifest 文件：

```text
size   = 该文件在 ZIP 中的实际字节数
sha256 = SHA-256(该文件在 ZIP 中的实际字节)，输出小写 hex
```

`module/rules.json` 中每条 `RuleEvidence.source.sourceHash` 也必须等于其 source 文件的同一 SHA-256；导入时逐条比对（`SOURCE_HASH_MISMATCH`）。

引擎另外计算一个内部 `contentHash`：把 manifest 的 `files` 按 `path` 排序后重新序列化再 SHA-256。它不出现在 manifest 中，作者不需要填写；但同一 `moduleId + version` 不同内容会在安装时报 `MODULE_VERSION_CONFLICT`。

## 3. 通用类型

### 3.1 `Audience` / `Visibility`

`Audience` 是枚举，取值必须完全一致：

```text
PUBLIC / GM / NPC / FACTION
```

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `audience` | Audience | 必填 | 可见对象类别 |
| `subjectId` | String? | 可选，默认 `null` | 具体主体 id；仅 `NPC` / `FACTION` 使用 |

`validVisibility` 规则：

- `PUBLIC`：`subjectId` 必须为 `null`（不是空字符串，必须是 `null` 或省略）。
- `GM`：`subjectId` 必须为 `null`。
- `NPC` / `FACTION`：`subjectId` 必须非空（空白字符串也会失败）。
- 代码不校验 `subjectId` 是否真的存在。

不合法时在不同位置报 `INVALID_VISIBILITY`。`RuleEvidence` 在导入阶段就会校验；世界的 `Fact.visibility` 在规则适配器阶段校验。

### 3.2 `SourceRef`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `sourceId` | String | 必填 | source 的 ZIP 路径，例如 `module/sources/rules.md`；必须是 `module/sources/` 下的文件。导入器按 ZIP 路径查文件并比对哈希；安装阶段只从 sources 映射取，所以不能指向 world/rules/manifest |
| `sourceHash` | String | 必填 | source 文件字节的 SHA-256，小写 hex |
| `anchor` | String | 必填 | 原文锚点/标题；证据要求非空。代码不校验锚点是否真实存在 |
| `authority` | String | 必填 | 来源权威。证据本身不校验；效果有单独限制，见第 6 节 |

### 3.3 `RuleEvidence`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `ruleId` | String | 必填 | 规则/证据 id；行动 `ruleId` 和 `ruleRefs` 会按它匹配。代码**不要求唯一**，但强烈建议唯一且稳定 |
| `source` | SourceRef | 必填 | 来源；`sourceId` 必须是 ZIP 内真实文件，`sourceHash` 必须匹配 |
| `quote` | String | 必填 | 原文逐字子串；不能为空。导入阶段要求 `sourceText.contains(quote)`，否则 `INVALID_RULE_QUOTE`；安装阶段额外要求长度 ≤ 1200，否则 `RULE_QUOTE_TOO_LARGE` |
| `visibility` | Visibility | 必填 | 该证据对谁可见；结算时只有 `PUBLIC` 证据进入玩家可见结果 |

证据本身的 `source.authority` 不做白名单校验；但行动效果会复制它并再次校验，所以建议规则证据用 `module_rule`、设定/主持证据用 `module_custom`。

`quote` 的要求：

1. 必须来自最终 `module/sources/...` 文件的**解码后文本**，不是原始资料的另一份副本。
2. 必须逐字匹配，包括标点、空格、换行、全半角。不能加省略号、不能改错别字、不能拼接两段。
3. 导入器不要求 quote 与 `anchor` 对应；`anchor` 只是定位提示。
4. 引用长度不超过 1200 字符（安装期检查；字符数，不是字节数）。
5. 证据 `visibility=GM` 可以引用主持秘密；`PUBLIC` 证据会展示给玩家，不能含秘密。

### 3.4 `ResourceBounds`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `min` | Int | 必填 | 下界 |
| `max` | Int | 必填 | 上界；要求 `min <= max`（无自定义错误码） |

## 4. `ModuleWorld` 及其嵌套类型

### 4.1 `ModuleWorld`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `startLocationId` | String | 必填 | 开局地点；必须存在于 `locations`，否则 `MISSING_START_LOCATION` |
| `characters` | List<CharacterTemplate> | 必填 | 至少一个，否则 `MISSING_CHARACTER` |
| `locations` | List<Location> | 必填 | 所有地点 |
| `npcs` | List<NpcState> | 必填 | 所有 NPC |
| `items` | List<Item> | 必填 | 所有物品 |
| `facts` | List<Fact> | 必填 | 所有事实/线索 |
| `objectives` | List<Objective> | 必填 | 所有目标 |
| `actions` | List<ActionDefinition> | 必填 | 所有可执行行动 |
| `policy` | GenerationPolicy | 必填 | 运行期生成策略 |
| `d20Combatants` | List<D20Combatant> | 可选，默认 `[]` | d20 战斗者；`wuxia-d20-v1` 使用 |
| `schedule` | List<ScheduledEvent> | 可选，默认 `[]` | 计划事件；上限 256 |
| `initialFlags` | Map<String,String> | 可选，默认 `{}` | 初始标记；上限 256 项 |
| `clock` | ClockSpec? | 可选，默认 `null` | 显示用时钟 |

所有实体 id（`locations`、`npcs`、`items`、`facts`、`objectives`、`characters`、`actions`、`schedule`）必须：

- 全局唯一（跨这些列表也不能重复）；
- 以 `${moduleId}:` 开头。

违反报 `INVALID_ENTITY_ID`。`d20Combatants` 的 id 不参与这条前缀/唯一性检查，但必须唯一，并且必须是某个 `characters` 或 `npcs` 的 id（`INVALID_COMBATANT`）；每个 character 必须有对应战斗者（`MISSING_COMBATANT`）。

### 4.2 `CharacterTemplate`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 实体 id，带 `moduleId:` 前缀且唯一 |
| `name` | String | 必填 | 角色名；代码不做非空/长度校验 |
| `skills` | Map<String,Int> | 必填 | 技能值。`generic-d100-v1` 要求 key 都在 `rules.skills` 中且值在 `0..100`，否则 `INVALID_SKILL`；`wuxia-d20-v1` 不使用这个字段 |
| `resources` | Map<String,Int> | 必填 | 初始资源。`generic-d100-v1` 的初始状态会把它作为 `WorldState.resources`，每个 key 都必须有 `rules.resourceBounds`，值必须在界内，否则 `NoSuchElementException` / 无自定义错误码失败；`wuxia-d20-v1` 不做资源边界校验 |

> 当前引擎按 `characters.first()` 创建初始角色：`generic-d100-v1` 取第一个角色的技能/资源；`wuxia-d20-v1` 的初始 d20 状态只纳入第一个角色与所有 NPC 战斗者，且 `validateState` 要求在场 PC 恰好 1 名。实际按**单玩家角色**设计模组；不要依赖多个可玩角色同时在场。

### 4.3 `Location`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 带前缀且唯一 |
| `title` | String | 必填 | 显示名；会展示给玩家，不能剧透 |
| `description` | String | 必填 | 地点描述；会展示给玩家，不能写秘密 |
| `exits` | List<String> | 必填 | 相邻地点 id；每个目标必须存在，否则 `DANGLING_EXIT` |

`exits` 可以有向/单向。移动效果要求目标在当前地点的 `exits` 中。

### 4.4 `NpcState`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 带前缀且唯一 |
| `name` | String | 必填 | NPC 名；玩家可见，不能剧透 |
| `locationId` | String | 必填 | 初始地点；必须存在，否则 `DANGLING_NPC_LOCATION` |
| `status` | String | 必填 | 初始状态。代码加载时不校验；运行期支持 `idle` / `helpful` / `hostile` / `busy` / `injured` / `departed`（效果校验使用这六个），d20 战败会写入 `defeated` |
| `personality` | String | 必填 | 性格；GM 侧使用 |
| `goal` | String | 必填 | 目标；GM 侧使用 |
| `relationships` | Map<String,Int> | 可选，默认 `{}` | 关系值；加载时不校验范围；效果 `npc_relation` 运行期把总值限制在 `-100..100` |
| `nextActionAt` | Long? | 可选，默认 `null` | 预留字段；当前模块校验不检查 |

### 4.5 `Fact`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 带前缀且唯一 |
| `text` | String | 必填 | 事实文本；`GM` 可见性时是秘密，不能出现在公开字段 |
| `visibility` | Visibility | 必填 | 必须通过 `validVisibility`，否则 `INVALID_VISIBILITY` |
| `locked` | Boolean | 必填 | 锁定事实。`reveal_fact` 命中锁定事实时受 `LOCKED_FACT` 限制 |
| `source` | SourceRef | 必填 | 来源。ZIP 导入路径**不校验** Fact.source 的存在/哈希；建议直接复制某条证据的 source |

`fact_known` 条件只把 `visibility.audience == PUBLIC` 的事实视为“已知/公开”。`GM` 事实在 `reveal_fact` 之前不会满足该条件。

### 4.6 `Objective`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 带前缀且唯一 |
| `title` | String | 必填 | 目标标题；玩家可见，不能剧透 |
| `chapter` | Int | 必填 | 章节序号；加载时不校验 |
| `status` | String | 必填 | 初始状态；加载时不校验。效果/条件只接受 `active` / `complete` / `failed` |

### 4.7 `Item`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 带前缀且唯一 |
| `title` | String | 必填 | 物品名；玩家拥有时会显示 |
| `ownerId` | String | 必填 | 初始拥有者。代码加载时不校验；运行期公开视图只展示 `ownerId == "player"` 的物品。推荐用 `player` 或 NPC id |
| `quantity` | Int | 必填 | 数量；加载时不校验 |

### 4.8 `GenerationPolicy`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `allowedEntityTypes` | Set<String> | 必填 | 只允许 `npc` / `location_detail` 的子集；否则 `INVALID_GENERATION_POLICY` |
| `maxEntities` | Int | 必填 | 必须在 `0..100`，否则 `INVALID_GENERATION_POLICY` |
| `lockedFactIds` | Set<String> | 必填 | 每个 id 必须指向一个 `locked=true` 的事实，否则无自定义错误码失败 |

`GenerationPolicy` 只影响引擎运行期生成（AI 提案的 creations），不影响模组作者在 `world.json` 里写好的实体。模组行动本身没有 creations 字段。

### 4.9 `ActionDefinition`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 带前缀且唯一 |
| `title` | String | 必填 | 行动标题；模块校验时作为 `publicSummary`，要求非空且 ≤ 2000 字符（无错误码）；会展示给玩家，不能剧透 |
| `actionType` | String | 必填 | 见下方“行动类型” |
| `targetIds` | List<String> | 必填 | 目标 id；每个目标必须存在（`UNKNOWN_TARGET`） |
| `skill` | String? | 必填，可为 `null` | 技能。`generic-d100-v1`：非空时必须在初始 `world.skills` 中（`UNKNOWN_SKILL`），`skill_check` 必须非空（`SKILL_REQUIRED`）。`wuxia-d20-v1`：必须为 `null`（`SKILL_NOT_SUPPORTED`） |
| `difficulty` | String | 必填 | `generic-d100-v1` 必须是 `rules.difficulties` 的 key（`UNKNOWN_DIFFICULTY`）；`wuxia-d20-v1` 不校验，但仍必填 |
| `ruleId` | String | 必填 | 必须匹配至少一条 `rules.evidence[].ruleId`（`RULE_NOT_FOUND`） |
| `conditions` | List<Condition> | 必填 | 全部满足才可用；总数 ≤ 64（`CONDITION_LIMIT`），深度限制见第 5 节 |
| `successEffects` | List<Effect> | 必填 | 成功分支；≤ 16 条（`EFFECT_LIMIT`），可为空 |
| `failureEffects` | List<Effect> | 必填 | 失败分支；≤ 16 条（`EFFECT_LIMIT`），可为空 |
| `check` | ActionCheck? | 可选，默认 `null` | 仅 `wuxia-d20-v1` 使用；有 `check` 当且仅当 `actionType=="d20"`，否则 `ACTION_CHECK_MISMATCH` |

行动类型：

| 适配器 | 允许的 `actionType` |
| --- | --- |
| `generic-d100-v1` | `investigate` / `move` / `negotiate` / `use_item` / `wait` / `skill_check` |
| `wuxia-d20-v1` | `investigate` / `move` / `negotiate` / `use_item` / `wait` / `d20` |

模块加载时作者行动会按“玩家位于该行动的地点条件”预先走一遍提案校验：

- 行动地点 = 第一个 `type=="location"` 条件的 `targetId`；没有则用 `startLocationId`。
- `generic-d100-v1` 的 `investigate` / `negotiate` / `use_item` / `skill_check` 目标若是“不在当前地点的 NPC”，报 `TARGET_NOT_PRESENT`。
- `wuxia-d20-v1` 的 d20 目标必须在行动地点在场，否则 `TARGET_NOT_PRESENT`。
- `move` / `npc_status` / `npc_relation` / `knowledge` / `transfer_item` 等效果也按这个“行动地点”做静态校验。因此这类行动应显式写 `location` 条件，并保证 NPC/物品/出口在该地点成立。
- `wuxia-d20-v1` 的 `actionType=="d20"` 行动，其 `check` 会被转换成一次 `kind=="check"` 的 d20 行动；该检查要求 `targetIds` 为空、`ActionDefinition.skill` 为 `null`、`ActionCheck.skill` 存在于 `rules.d20.skills`、`dcTier` 存在于 `rules.d20.dcTiers`，否则 `INVALID_D20_ACTION` / `UNKNOWN_DC_TIER`。

`ActionCheck`：

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `skill` | String | 必填 | 必须存在于 `rules.d20.skills` |
| `dcTier` | String | 必填 | 必须存在于 `rules.d20.dcTiers` |

### 4.10 `ClockSpec`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `startLabel` | String | 必填 | 起始标签；必须属于 `labels`，否则 `INVALID_CLOCK` |
| `minutesPerUnit` | Int | 必填 | 每格分钟数，`1..100000`，否则 `INVALID_CLOCK` |
| `labels` | List<String> | 必填 | 1–48 个，互不重复，全部非空且长度 ≤ 20；否则 `INVALID_CLOCK` |

时钟仅影响显示：引擎按 `labels` 循环，时间 0 落在 `startLabel`，显示为“第 N 日 标签”。

## 5. `Condition` 全部类型

`Condition` 对象：

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `type` | String | 必填 | 条件类型，见下表 |
| `targetId` | String? | 可选，默认 `null` | 目标实体 id |
| `key` | String? | 可选，默认 `null` | 键（资源/标记/关系） |
| `value` | String? | 可选，默认 `null` | 值（数字/状态/地点/文本） |
| `children` | List<Condition> | 可选，默认 `[]` | 子条件 |

公共校验：

- `type` 必须是下表之一，否则 `UNKNOWN_CONDITION`。
- 所有节点（包括叶子）的 `children` 都会递归校验。
- 通过 `GenericD100.count` 计入数量/深度限制的场景（行动 `conditions`、计划事件 `conditions + cancelIf`）：节点总数 ≤ 64（`CONDITION_LIMIT`）；深度参数从 0 开始，递归到 8 仍允许，超过报 `CONDITION_DEPTH`（即最多 9 层，根 + 8 层子节点）。
- **章节 `enterWhen` 是例外**：`validateChapters` 只调用 `validateCondition` 递归校验类型/参数，不调用 `count`，因此这条路径没有 64 节点/深度上限。作者仍应保持条件简洁。
- 模块加载时按初始世界校验；运行期按当前世界判断。
- 字符串数字用 `toIntOrNull` / `toLongOrNull`；不合法会失败。

| `type` | 必填参数 | 校验 | 运行语义 |
| --- | --- | --- | --- |
| `all` | `children` 非空 | 否则无自定义错误码失败 | 所有子条件为真 |
| `any` | `children` 非空 | 否则无自定义错误码失败 | 任一子条件为真 |
| `not` | `children` 恰好 1 个 | 否则无自定义错误码失败 | 子条件取反 |
| `location` | `targetId` | 必须是已有 location id | 当前地点等于 `targetId` |
| `resource_gte` | `key`、`value` | `key` 必须在初始 `world.resources` 中；`value` 必须能解析为 Int | 当前资源 `>= value` |
| `fact_known` | `targetId` | 必须是已有 fact id | 存在该 fact 且 `visibility.audience == PUBLIC` |
| `objective` | `targetId`、`value` | 目标必须是已有 objective；`value` ∈ `active` / `complete` / `failed` | 该目标状态等于 `value` |
| `time_gte` | `value` | 必须能解析为 Long；不限制正负 | `gameTime >= value` |
| `flag_eq` | `key`、`value` | `key` 非空且 `value` 不为 `null` | `world.flags[key] == value` |
| `npc_at` | `targetId`、`value` | NPC 存在；`value` 必须是已有 location | 该 NPC 当前地点等于 `value` |
| `item_owner` | `targetId`、`value` | 物品存在；`value` 是 `"player"`、已有 NPC id 或已有 location id | 该物品 `ownerId == value` |
| `schedule_state` | `targetId`、`value` | 计划事件存在；`value` ∈ `pending` / `armed` / `fired` / `skipped` / `cancelled` | 该计划事件状态等于 `value` |

注意：

- `fact_known` 不看 `knowledge` 边，只看 fact 本身是否 `PUBLIC`。
- `flag_eq` 的 value 允许空字符串（只要不是 `null`）。
- `item_owner` 允许 location id，虽然物品初始拥有者通常只写 `player` 或 NPC。
- 条件树中的 `targetId` 不会被要求带 `moduleId:` 前缀，但实际实体 id 都带前缀，按原值精确匹配。

## 6. `Effect` 全部类型

`Effect` 对象：

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `type` | String | 必填 | 效果类型 |
| `targetId` | String | 必填 | 目标实体 id（部分类型必须是 `player` / `world`） |
| `key` | String? | 可选，默认 `null` | 资源键/关系维度/标记键 |
| `amount` | Int? | 可选，默认 `null` | 数值 |
| `value` | String? | 可选，默认 `null` | 字符串值 |
| `source` | SourceRef | 必填 | 效果依据；必须匹配证据来源 |

### 6.1 来源规则

`NarrativeEffects.validate` 对普通行动效果：

- `source.authority` 必须 ∈ `module_rule` / `module_custom` / `temporary_ruling`，否则 `INVALID_EFFECT_SOURCE`。
- 若 `authority` 不是 `temporary_ruling` 也不是 `generated`，必须存在一条 `RuleEvidence.source` 与它**完全相等**（`sourceId`、`sourceHash`、`anchor`、`authority` 都相同），否则 `INVALID_EFFECT_SOURCE`。
- 模块作者的行动不能使用 `temporary_ruling`（校验会要求 `temporaryRuling && requiresConfirmation`，报 `RULING_CONFIRMATION_REQUIRED`）。所以模块内效果应使用 `module_rule` 或 `module_custom`，并复制证据的完整 `SourceRef`。
- `generated` 在普通效果中实际会被第一道白名单拒绝；不要使用。
- 行动效果还必须出现在 `rules.allowedEffects` 中，否则 `UNKNOWN_EFFECT`。

计划事件效果更严格：`authority` 必须是 `module_rule`，且必须匹配某条证据的 source，否则 `INVALID_EFFECT_SOURCE`。

### 6.2 普通叙事效果（行动成功/失败分支）

| `type` | 适用适配器 | 必填参数与校验 | 应用 |
| --- | --- | --- | --- |
| `move` | 两者 | `targetId` 是已有 location，且当前地点 `exits` 包含它，否则 `UNREACHABLE` | 玩家移动到目标地点 |
| `resource` | 仅 `generic-d100-v1` | `targetId=="player"`；`key` 在 `resourceBounds` 中；`amount` 非空且在 `-1000..1000` | 资源加减；结果超出 bounds 报 `RESOURCE_BOUNDS` |
| `transfer_item` | 仅 `generic-d100-v1` | 物品存在；`value` 是 `"player"` 或当前地点在场的 NPC；否则失败 | 改物品 `ownerId=value` |
| `reveal_fact` | 两者 | 事实存在 | 把事实可见性改为 `PUBLIC` |
| `objective` | 两者 | 目标存在；`value` ∈ `active` / `complete` / `failed` | 改目标状态 |
| `npc_status` | 两者 | NPC 存在且在当前地点；`value` ∈ `idle` / `helpful` / `hostile` / `busy` / `injured` / `departed` | 改 NPC 状态 |
| `npc_relation` | 两者 | NPC 在当前地点；`key` ∈ `trust` / `fear` / `respect`；`amount` 非空且 `-20..20` | 关系值加减；结果超出 `-100..100` 报无错误码失败 |
| `knowledge` | 两者 | NPC 在当前地点；事实存在且 `visibility.audience == PUBLIC`，否则 `NPC_CANNOT_KNOW` | 建立 NPC 知情边（`method="heard"`, `belief="believed"`） |
| `time` | 两者 | `targetId=="world"`；`amount` 非空且 `1..1440` | 推进 `gameTime` |

对“当前地点”的静态校验以行动地点为准（见 `ActionDefinition`）。例如一个在 A 地点使用的行动，`move` 目标必须是 A 的出口，`npc_status` 目标 NPC 必须初始位于 A。

`wuxia-d20-v1` 的叙事效果白名单是：

```text
move / reveal_fact / knowledge / npc_status / npc_relation / objective / time
```

`generic-d100-v1` 的白名单是：

```text
move / resource / transfer_item / reveal_fact / objective / npc_status / npc_relation / knowledge / time
```

### 6.3 计划事件效果（仅 `ScheduledEvent.effects`）

计划事件效果不受 `rules.allowedEffects` 限制，由 `Scheduler.effectKinds` 单独控制：

| `type` | 参数与校验 | 应用 |
| --- | --- | --- |
| `npc_move` | `targetId` 是已有 NPC；`value` 是已有 location | 改 NPC 地点 |
| `npc_status` | `targetId` 是已有 NPC；`value` ∈ 六种状态 | 改 NPC 状态（不要求同地点） |
| `transfer_item` | 物品存在；`value` 是 `"player"`、任一 NPC 或任一 location | 改物品拥有者 |
| `knowledge` | `targetId` 是已有 NPC；`value` 是已有 fact（Scheduler 不要求该 fact 为 `PUBLIC`） | 建立知情边 |
| `objective` | 目标存在；`value` ∈ `active` / `complete` / `failed` | 改目标状态 |
| `set_flag` | `targetId=="world"`；`key` 非空且 ≤ 64；`value` ≤ 200（允许空字符串） | 写 `world.flags` |
| `reveal_fact` | 事实存在 | 事实改 `PUBLIC` |

不合法时分别报 `UNKNOWN_EFFECT`（类型不在白名单）、`INVALID_EFFECT_SOURCE`（来源不匹配）、`INVALID_SCHEDULE_EFFECT`（参数错误）。

## 7. `ScheduledEvent`、状态与时钟

### 7.1 `ScheduledEvent`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 带 `moduleId:` 前缀且全局唯一（参与 `INVALID_ENTITY_ID` 检查） |
| `label` | String | 必填 | GM 侧标签，非空且 ≤ 200；**永不展示给玩家** |
| `atMinute` | Long? | 可选，默认 `null` | 绝对分钟；非空时必须 ≥ 0，且 `delayMinutes` 必须为 0，否则 `INVALID_SCHEDULE` |
| `conditions` | List<Condition> | 可选，默认 `[]` | 触发条件；`atMinute==null` 时不能为空 |
| `delayMinutes` | Int | 可选，默认 `0` | 条件满足后延迟多少分钟触发；`0..100000` |
| `cancelIf` | List<Condition> | 可选，默认 `[]` | 取消条件 |
| `effects` | List<Effect> | 必填 | 触发时应用；**1–16 条**，不能为空 |
| `notice` | String? | 可选，默认 `null` | 触发时的“征兆”，会进入玩家可见通知；非空时长度 ≤ 500，不能泄密 |

公共限制：

- `world.schedule.size <= 256`，否则 `INVALID_SCHEDULE`。
- `conditions + cancelIf` 的节点总数 ≤ 64，否则 `CONDITION_LIMIT`。
- 事件条件在模块加载时按初始世界用 `GenericD100.validateCondition` 校验；运行期按实时世界判断。
- 效果在模块加载时按初始世界校验（`Scheduler.validateEffect`），因此 NPC/物品/地点必须在初始状态存在。

### 7.2 生命周期

初始状态全部是 `pending`。每次玩家行动结算后，在行动产生的 `gameTime` 上按 id 排序运行调度器（`Scheduler.run`）：

1. 对每个 `pending` / `armed` 事件检查 `cancelIf`；第一个命中的事件变为 `cancelled`。
2. 对 `atMinute==null` 且 `pending` 的事件，若 `conditions` 全部成立，则变为 `armed`，`fireAt = 当前时间 + delayMinutes`。
3. 对于已到期的 `pending`（定时事件，`atMinute <= now`）或 `armed`（`fireAt <= now`）事件，取最早的一个：
   - 定时事件到期时若条件不成立 → `skipped`（不应用效果）；
   - 否则 → `fired`，应用 `effects`，`notice` 追加到结算通知。
   - 条件事件一旦 armed，到期时不再复查条件。
4. 循环直到没有可处理事件；一次结算最多处理 64 个触发/跳过，超过报 `SCHEDULE_LOOP`（通常意味着事件互相触发形成循环）。

状态只能是 `pending` / `armed` / `fired` / `skipped` / `cancelled`。`ScheduleStatus` 和 `ScheduledFiring` 是引擎记录，不写进模组包。

### 7.3 `initialFlags`

`world.initialFlags` 是 `Map<String,String>`：

- 最多 256 项，否则 `INVALID_SCHEDULE`；
- key 非空且 ≤ 64 字符；
- value 长度 ≤ 200（允许空字符串）。

运行期 `world.flags` 也受同样限制，超过报 `INVALID_FLAGS`。

## 8. `ModuleRules`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `adapterId` | String | 必填 | `generic-d100-v1` 或 `wuxia-d20-v1`；未知值报 `UNKNOWN_RULE_ADAPTER` |
| `adapterVersion` | String | 必填 | 当前必须为 `"1"` |
| `skills` | Set<String> | 必填 | 技能名集合。`generic-d100-v1` 用于角色技能/提案技能校验；`wuxia-d20-v1` 不直接使用（d20 用 `rules.d20.skills`） |
| `resourceBounds` | Map<String,ResourceBounds> | 必填 | 资源边界。`generic-d100-v1` 必须非空且每个 `min<=max`（否则无错误码失败）；`wuxia-d20-v1` 不做边界校验 |
| `difficulties` | Map<String,Int> | 必填 | 难度名→修正。`generic-d100-v1` 必须非空（无错误码失败），行动 `difficulty` 必须是 key；`wuxia-d20-v1` 不校验 |
| `allowedEffects` | Set<String> | 必填 | 行动效果白名单；必须是适配器效果集合的子集，否则 `UNKNOWN_EFFECT` |
| `evidence` | List<RuleEvidence> | 必填 | 证据列表；导入时逐条校验可见性、source hash、quote |
| `timeUnit` | String | 必填 | 时间单位显示文本（例如 `分钟`）；代码不校验 |
| `d20` | D20Rules? | 可选，默认 `null` | `wuxia-d20-v1` 必须非空，否则 `MISSING_D20_RULES`；`generic-d100-v1` 不使用 |
| `contentLevels` | List<ContentLevel> | 可选，默认 `[]` | 内容分档；见 8.1 |
| `defaultContentLevel` | Int | 可选，默认 `0` | 默认分档索引；见 8.1 |
| `chapters` | List<ChapterDefinition> | 可选，默认 `[]` | 章节；见 8.2 |

`adapterId + adapterVersion` 也出现在 manifest 的 `rulesetId + rulesetVersion`，两者必须一致（`RULE_VERSION_MISMATCH`）。

### 8.1 `ContentLevel` 与 `contentLevels`

`ContentLevel`：

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `label` | String | 必填 | 分档标签，会显示给玩家 |
| `narratorGuideline` | String | 必填 | 旁白指导，注入 narrator 提示 |

校验（`validateContentLevels`）：

- `contentLevels` 为空时，`defaultContentLevel` 必须为 `0`，否则 `INVALID_CONTENT_LEVEL`。
- 非空时最多 10 个，否则 `INVALID_CONTENT_LEVEL`。
- 每个 `label` 非空且长度 ≤ 40；每个 `narratorGuideline` 非空且长度 ≤ 2000；否则 `INVALID_CONTENT_LEVEL`。
- `defaultContentLevel` 必须在 `contentLevels.indices`（0-based）内，否则 `INVALID_CONTENT_LEVEL`。
- 运行期可以用 UI 调整当前分档；关闭内容分档不是自动行为。

### 8.2 `ChapterDefinition`

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 章节 id；非空，且在 chapters 内唯一 |
| `title` | String | 必填 | 章节标题；非空，会显示给玩家，不能剧透 |
| `enterWhen` | List<Condition> | 可选，默认 `[]` | 进入条件 |

校验（`validateChapters`）：

- `chapters` 为空时完全跳过。
- 所有 id 非空且互不重复，所有 title 非空，否则 `INVALID_CHAPTER`。
- **第一章 `enterWhen` 必须为空**；后续每一章 `enterWhen` 必须非空，否则 `INVALID_CHAPTER`。
- 每个条件都用 `GenericD100.validateCondition` 按初始世界校验（`UNKNOWN_CONDITION` 及各类型参数错误）。注意这条路径不调用 `count`，所以章节条件没有 64 节点/深度上限。
- 章节按声明顺序只向前推进；进入后记录 `ChapterMark`，`ordinal = 索引 + 1`。结算后若连续下一章的 `enterWhen` 全部成立，可连续推进多章。
- 第一章自动作为开局章节。章节标题会进入 UI/报告，按剧透处理。

## 9. `wuxia-d20-v1` 的 d20 规则块

`ModuleRules.d20` 必须是一个 `D20Rules`。这个适配器把加值、DC、骰子、伤害、上限、状态都交给引擎；模组只提供数据。

### 9.1 `D20Rules`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `attributes` | List<String> | 必填 | 属性名；非空且互不重复，否则 `INVALID_D20_RULES` |
| `skills` | Map<String,String> | 必填 | 技能名→属性名；每个值必须在 `attributes` 中，否则 `INVALID_D20_RULES` |
| `dcTiers` | Map<String,Int> | 必填 | DC 档名→DC；非空且值在 `1..40`，否则 `INVALID_D20_RULES` |
| `pools` | Map<String,PoolRule> | 必填 | 资源池（生命/内力等） |
| `counters` | Map<String,CounterRule> | 必填 | 计数器（资源点等） |
| `conditions` | Map<String,ConditionRule> | 必填 | 状态表 |
| `cards` | Map<String,CardRule> | 必填 | 卡牌/招式表 |
| `rest` | RestRule | 必填 | 休息规则 |
| `saveSuccessNegatesPoolLoss` | Boolean | 必填 | 卡牌 resolution=save 且目标豁免成功时，是否取消该卡对目标的 pool 损失 |
| `defeatPoolIds` | Set<String> | 必填 | 这些池归零即“战败”；每个都必须在 `pools` 中，否则 `INVALID_D20_RULES` |
| `concentrationAttribute` | String | 必填 | 专注豁免属性；必须在 `attributes` 中，否则 `INVALID_D20_RULES` |

### 9.2 `PoolRule`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `label` | String | 必填 | 显示名；代码不校验非空/长度，但应可读 |
| `perRoundLossCap` | Int? | 可选，默认 `null` | 每轮损失上限；非空时必须 ≥ 0，否则 `INVALID_POOL` |
| `capGroup` | String? | 可选，默认 `null` | 共享每轮上限的组名；不校验是否存在 |
| `damageSourceOnly` | Set<String> | 可选，默认 `{}` | 只允许这些伤害 tag 扣池（武器 tag / 卡牌效果 tag）；空集表示不限 |
| `atZeroCondition` | String? | 可选，默认 `null` | 归零时附加的状态；必须在 `conditions` 中，否则 `UNKNOWN_CONDITION` |
| `atZeroDuration` | DurationSpec? | 可选，默认 `null` | 归零状态持续时间；非空时按 9.3 校验，否则 `INVALID_DURATION` |
| `singleHitCannotEmptyFromFull` | Boolean | 可选，默认 `false` | 满池时单次伤害不能直接清空（至少留 1） |
| `restoreOn` | String | 必填 | 休息恢复模式：`short` / `long` / `none`，否则 `INVALID_POOL` |

运行期顺序（代码注释明确）：伤害来源检查 → 每轮上限 → 满血不能一击清空 → 不低于 0 → 归零状态/战败。正数表示损失，负数表示恢复。

### 9.3 `DurationSpec`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `unit` | String | 必填 | `rounds` / `minutes` / `until_target_turn_start` / `until_actor_turn_end` / `until_removed`；否则 `INVALID_DURATION` |
| `value` | Int | 可选，默认 `1` | `1..100000`，否则 `INVALID_DURATION` |

### 9.4 `CounterRule`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `label` | String | 必填 | 显示名；不校验非空 |
| `restoreOn` | String | 必填 | `short` / `long` / `none`，否则 `INVALID_COUNTER` |

### 9.5 `ConditionRule`

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `label` | String | 必填 | 状态显示名 |
| `text` | String | 必填 | 状态说明 |
| `blocksActions` | Boolean | 可选，默认 `false` | 禁止行动 |
| `speedZero` | Boolean | 可选，默认 `false` | 速度归零（数据字段；当前校验不额外检查） |
| `attackDisadvantage` | Boolean | 可选，默认 `false` | 攻击劣势 |
| `attackedAdvantage` | Boolean | 可选，默认 `false` | 被攻击时对方优势 |
| `autoFailSaves` | Set<String> | 可选，默认 `{}` | 这些属性的豁免自动失败；每个必须在 `attributes` 中，否则 `INVALID_CONDITION` |
| `endsOnDamage` | Boolean | 可选，默认 `false` | 受到生命池伤害时移除 |

`label` / `text` 没有长度校验。

### 9.6 `RestRule`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `shortMinutes` | Int | 必填 | 短休分钟，必须 > 0，否则 `INVALID_D20_RULES` |
| `longMinutes` | Int | 必填 | 长休分钟，必须 > 0，否则 `INVALID_D20_RULES` |
| `hitPoolId` | String | 必填 | 生命池 id，必须在 `pools` 中，否则 `INVALID_D20_RULES` |

短休恢复 `restoreOn=="short"` 的池/计数器；长休恢复 `short` 与 `long`。卡牌槽按战斗者的 `slotRestoreOn` 恢复。

### 9.7 `CardRule`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `label` | String | 必填 | 显示名 |
| `text` | String | 必填 | 说明 |
| `timing` | String | 必填 | `action` / `bonus` / `reaction` / `free`，否则 `INVALID_CARD` |
| `isSpell` | Boolean | 必填 | 是否法术 |
| `slotLevel` | Int? | 可选，默认 `null` | 最低环位；非空时必须 ≥ 1，否则 `INVALID_CARD`。为 `null` 时不消耗槽；非空时可用不低于它的槽位（最高到角色 `slots` 的最大 key），越界报 `SLOT_LEVEL_INVALID` / `SLOT_EMPTY` |
| `counterCost` | Map<String,Int> | 可选，默认 `{}` | 计数器消耗；key 必须在 `counters` 中且值 > 0，否则 `INVALID_CARD` |
| `concentration` | Boolean | 可选，默认 `false` | 需要专注；再次施放会结束旧专注 |
| `maxTargets` | Int | 可选，默认 `1` | 目标数上限，必须 `1..6`，否则 `INVALID_CARD` |
| `resolution` | String | 必填 | `save` / `attack` / `contest` / `auto`，否则 `INVALID_CARD` |
| `keyAttribute` | String? | 可选，默认 `null` | 关键属性；按 resolution 校验 |
| `saveAttribute` | String? | 可选，默认 `null` | 豁免属性；按 resolution 校验 |
| `saveAdvantageWhenHostile` | Boolean | 可选，默认 `false` | 目标不是施法者时豁免有优势 |
| `contestSkill` | String? | 可选，默认 `null` | 对抗技能；必须存在于 `skills` |
| `contestDefenseSkills` | Set<String> | 可选，默认 `{}` | 防御方可用的技能；非空且都在 `skills` 中 |
| `tieGoesToActor` | Boolean | 可选，默认 `false` | 对抗平局时施法者胜 |
| `requiresTargetCondition` | Set<String> | 可选，默认 `{}` | 每个目标必须至少拥有其中一个状态；状态必须在 `conditions` 中 |
| `requiresActorNotCondition` | Set<String> | 可选，默认 `{}` | 施法者不能拥有其中任一状态；状态必须在 `conditions` 中 |
| `onSuccess` | List<CardEffect> | 可选，默认 `[]` | 成功分支 |
| `onFailure` | List<CardEffect> | 可选，默认 `[]` | 失败分支 |
| `onTargetSaveSuccessToActor` | List<CardEffect> | 可选，默认 `[]` | 目标豁免成功时对施法者追加的效果 |
| `repeatSave` | Boolean | 可选，默认 `false` | 附加状态可重复豁免；为 true 时 `saveAttribute` 必须非空，否则 `INVALID_CARD` |

按 `resolution` 额外要求：

| `resolution` | 额外必填项 | 未满足时 |
| --- | --- | --- |
| `auto` | 无 | — |
| `save` | `keyAttribute` 与 `saveAttribute` 都在 `attributes` 中 | `INVALID_CARD` |
| `attack` | `keyAttribute` 在 `attributes` 中 | `INVALID_CARD` |
| `contest` | `contestSkill` 在 `skills` 中；`contestDefenseSkills` 非空且都在 `skills` 中 | `INVALID_CARD` |

`repeatSave==true` 时还要求 `saveAttribute != null`，否则 `INVALID_CARD`。

Resolution 的运行语义：

- `auto`：直接成功，应用 `onSuccess`。
- `contest`：施法者用 `contestSkill`，防御方用 `contestDefenseSkills` 中最高技能；成功应用 `onSuccess`，失败应用 `onFailure`。
- `attack`：攻击检定 `d20 + keyAttribute 调整 + 熟练加值` 对抗 AC；命中应用 `onSuccess`，未命中应用 `onFailure`；暴击时伤害骰翻倍（固定加值不翻倍）。
- `save`：目标用 `saveAttribute` 对 DC 豁免；**豁免失败 → `onSuccess`**，**豁免成功 → `onFailure` + `onTargetSaveSuccessToActor`**。这里的 `onFailure` 是“卡牌未生效”的分支。
- 卡牌豁免 DC = `8 + 施法者熟练加值 + keyAttribute 调整`。
- 卡牌目标数在运行时必须满足 `1..maxTargets`，否则 `TARGET_COUNT`。
- 施法者必须拥有该卡（`c.cards` 包含卡 id），否则 `CARD_NOT_OWNED`。

Timing：

- 同一 actor 在一次 d20 行动中，`action` / `bonus` / `reaction` 各自只能使用一次；重复报 `TIMING_ALREADY_USED`。`free` 不占用。
- 如果本回合使用了一个 `timing=="bonus" && isSpell` 的卡，之后再用 `timing=="action"` 的法术，该法术必须是 `slotLevel==null` 的戏法，否则 `BONUS_SPELL_RULE`。

### 9.8 `CardEffect`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `type` | String | 必填 | `pool` / `condition_add` / `condition_remove` / `counter`；否则 `INVALID_CARD_EFFECT` |
| `target` | String | 必填 | `target` / `actor`；否则 `INVALID_CARD_EFFECT` |
| `pool` | String? | 可选，默认 `null` | `type=="pool"` 时必须存在于 `pools`，否则 `UNKNOWN_POOL` |
| `counter` | String? | 可选，默认 `null` | `type=="counter"` 时必须存在于 `counters`，否则 `UNKNOWN_COUNTER` |
| `dice` | String? | 可选，默认 `null` | 骰子表达式；与 `amount` **恰好二选一**，否则 `INVALID_CARD_EFFECT`。格式见 9.11 |
| `amount` | Int? | 可选，默认 `null` | 固定数值；`-1000..1000`，否则 `INVALID_CARD_EFFECT` |
| `condition` | String? | 可选，默认 `null` | 状态 id；`condition_add` / `condition_remove` 必须存在于 `conditions`，否则 `UNKNOWN_CONDITION` |
| `duration` | DurationSpec? | 可选，默认 `null` | `condition_add` 的持续时间；为 `null` 表示不自动过期（直到被移除） |
| `tag` | String | 必填 | 非空；伤害来源/显示标签，参与 `damageSourceOnly` 匹配 |
| `ifTargetHasCondition` | String? | 可选，默认 `null` | 仅当目标拥有该状态时应用；必须在 `conditions` 中 |

符号约定：

- `pool`：正数/正骰为损失，负数/负骰为恢复；结果仍受池边界、每轮上限、`singleHitCannotEmptyFromFull` 等约束。
- `counter`：正数/正骰为消耗（减少），负数为恢复。
- `condition_add`：添加状态；`condition_remove`：移除同名状态。
- `target=="target"` 作用于卡牌目标，`target=="actor"` 作用于施法者。

### 9.9 `D20Combatant`

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 必须是某个 character 或 NPC 的 id；唯一；否则 `INVALID_COMBATANT` |
| `name` | String | 必填 | 非空，否则 `INVALID_COMBATANT` |
| `level` | Int | 必填 | `1..30`，否则 `INVALID_COMBATANT` |
| `scores` | Map<String,Int> | 必填 | key 集合必须**正好等于** `attributes`，值 `1..30`，否则 `INVALID_COMBATANT` |
| `proficiencyBonus` | Int | 必填 | `0..10`，否则 `INVALID_COMBATANT` |
| `saveProficiencies` | Set<String> | 必填 | 每个都在 `attributes` 中，否则 `INVALID_COMBATANT` |
| `skillRanks` | Map<String,Int> | 必填 | key 集合必须**正好等于** `skills` 的 key 集合，值 `0..2`，否则 `INVALID_COMBATANT` |
| `armorClass` | Int | 必填 | `0..60`，否则 `INVALID_COMBATANT` |
| `pools` | Map<String,PoolValue> | 必填 | key 都在 `pools` 中；必须包含 `rest.hitPoolId`；否则 `INVALID_COMBATANT` |
| `slots` | Map<Int,PoolValue> | 必填 | 环位→槽值；key 必须 ≥ 1；否则 `INVALID_COMBATANT` |
| `slotRestoreOn` | String | 必填 | `short` / `long`，否则 `INVALID_COMBATANT` |
| `counters` | Map<String,PoolValue> | 必填 | key 都在 `counters` 中；否则 `INVALID_COMBATANT` |
| `cards` | Set<String> | 必填 | 每个都在 `cards` 中，否则 `INVALID_COMBATANT` |
| `weaponAttacks` | Map<String,WeaponAttack> | 必填 | 武器 id→数据 |
| `conditions` | List<ActiveCondition> | 可选，默认 `[]` | 初始状态；每个 id 都在 `conditions` 中，否则 `INVALID_COMBATANT` |
| `concentration` | ActiveConcentration? | 可选，默认 `null` | 初始专注；`cardId` 必须在 `cards` 中，否则 `INVALID_COMBATANT` |

`PoolValue`：

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `current` | Int | 必填 | 当前值；必须 `0..max`，否则 `INVALID_COMBATANT` |
| `max` | Int | 必填 | 最大值；必须 ≥ 0，否则 `INVALID_COMBATANT` |

`WeaponAttack`：

| 字段 | 类型 | 必填 / 默认 | 含义与校验 |
| --- | --- | --- | --- |
| `label` | String | 必填 | 显示名；不校验非空 |
| `attribute` | String | 必填 | 必须在 `attributes` 中，否则 `INVALID_COMBATANT` |
| `proficient` | Boolean | 必填 | 是否加熟练 |
| `damage` | String | 必填 | 骰子表达式；非法报 `INVALID_DICE` |
| `tag` | String | 可选，默认 `"weapon"` | 非空，否则 `INVALID_COMBATANT`；参与 `damageSourceOnly` |

`ActiveCondition`：

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `id` | String | 必填 | 状态 id；模块初始状态必须在 `d20.conditions` 中 |
| `sourceId` | String | 必填 | 来源战斗者 id；不校验 |
| `sourceCard` | String? | 可选，默认 `null` | 来源卡 id；不校验 |
| `expiresRound` | Long? | 可选，默认 `null` | 到期轮 |
| `expiresMinute` | Long? | 可选，默认 `null` | 到期分钟 |
| `repeatSave` | RepeatSave? | 可选，默认 `null` | 可重复豁免 |

`RepeatSave`：

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `attribute` | String | 必填 | 豁免属性 |
| `dc` | Int | 必填 | 豁免 DC |

`ActiveConcentration`：

| 字段 | 类型 | 必填 / 默认 | 含义 |
| --- | --- | --- | --- |
| `cardId` | String | 必填 | 专注卡 id |
| `targetIds` | List<String> | 必填 | 目标 id 列表 |
| `expiresMinute` | Long? | 可选，默认 `null` | 到期分钟 |

### 9.10 d20 结算语义

- 属性调整 = `floor((score - 10) / 2)`。
- 技能加值 = 属性调整 + `proficiencyBonus * skillRanks[skill]`。
- 豁免加值 = 属性调整 +（若该属性在 `saveProficiencies` 中则加熟练）。
- 检定：`d20 + 技能/属性加值`，对抗 `dcTiers[dcTier]`；`>= DC` 成功。
- 优势/劣势：净优势掷 `2d20` 取高，净劣势取低；二者同时存在则抵消。优势值只能是 `-1..1`，非 0 必须提供 `advantageReason`。
- 对抗：双方各掷 d20 + 技能加值；`tieGoesToActor` 决定平局。
- 攻击：`d20 + 属性调整 + 熟练（若 proficient）` 对抗 AC；自然 20 必中且暴击，自然 1 必失；暴击时武器伤害的骰子部分再掷一次，固定加值只算一次。
- 武器伤害扣 `rest.hitPoolId`。卡牌 `pool` 效果按卡牌定义扣/恢复。
- 每轮损失上限按 `capGroup`（未设置则用池 id）分组；`roundLosses` 在轮次推进时清空。
- 生命池受伤会触发 `endsOnDamage` 状态移除；若正在专注，还要做 `concentrationAttribute` 专注豁免，DC = `max(10, 损失/2)`。
- 池归零触发 `atZeroCondition`（按 `atZeroDuration`）；若池在 `defeatPoolIds` 中，标记战败。
- 休息按 `restoreOn` 恢复池/计数器，按 `slotRestoreOn` 恢复槽，并推进 `shortMinutes` / `longMinutes`；长休同时移除所有带 `expiresMinute` 的状态。
- 每次 d20 结算结束推进轮次、清理到期状态、执行重复豁免、再按叙事时间效果清理分钟状态。

### 9.11 骰子表达式

`DiceExpression` 接受：

- `NdM` 或 `NdM+K` / `NdM-K`：`N` 为 1–20，`M` ∈ `{4,6,8,10,12,20,100}`，`K` 的绝对值 ≤ 50；
- 纯整数：`-50..50`。

其他形式报 `INVALID_DICE`。卡牌 `dice`、武器 `damage` 都使用这个格式。

## 10. id 与命名规则

| 对象 | 规则 |
| --- | --- |
| `moduleId` | `[a-z][a-z0-9-]{2,63}`，3–64 字符，小写字母开头 |
| `version` | `[0-9]+\.[0-9]+\.[0-9]+`，三段数字 |
| 实体 id | 必须 `${moduleId}:` 前缀；在 locations/npcs/items/facts/objectives/characters/actions/schedule 之间全局唯一 |
| `d20Combatants[].id` | 必须是 character 或 NPC 的 id；自身唯一 |
| source id | `module/sources/[A-Za-z0-9_-]+` + `.md` 或 `.txt` |
| `RuleEvidence.ruleId` | 无强制格式；建议 `${moduleId}:rule:...` 且唯一，便于行动引用 |
| 章节 id | 非空、在 chapters 内唯一；无前缀强制 |
| d20 的 `attributes` / `skills` / `dcTiers` / `pools` / `counters` / `conditions` / `cards` 的 key | 无强制字符集/前缀；必须被引用处精确匹配 |
| `initialFlags` key | 非空且 ≤ 64；value ≤ 200 |
| `Visibility.subjectId` | NPC/FACTION 时非空；不校验实体是否存在 |
| 武器 id | `weaponAttacks` 的 map key；行动 `weaponId` 必须匹配 |
| 槽位 key | 正整数（`1` 起）；JSON object 的 key 是字符串，解码为 Int |

不要用重复 id 表达“同名不同实体”；所有引用都是按 id 精确匹配。

## 11. 数量与长度限制速查

| 项 | 限制 | 错误码 |
| --- | --- | --- |
| ZIP 压缩包 | ≤ 20 MiB | `ZIP_SIZE_LIMIT` |
| ZIP 单条目 | ≤ 5 MiB | `ZIP_SIZE_LIMIT` |
| ZIP 展开总量 | ≤ 50 MiB | `ZIP_EXPANDED_LIMIT` |
| ZIP 条目数 | ≤ 2000 | `ZIP_ENTRY_LIMIT` |
| manifest/world/rules 单个 JSON | ≤ 262144 字节 | `JSON_LIMIT` |
| JSON 嵌套深度 | ≤ 64 层 | `JSON_DEPTH_LIMIT` |
| 行动/计划事件条件树节点 | ≤ 64（章节 enterWhen 不受此限） | `CONDITION_LIMIT` |
| 行动/计划事件条件树深度 | 深度参数 0..8（最多 9 层；章节 enterWhen 不受此限） | `CONDITION_DEPTH` |
| 行动成功/失败效果 | 各 ≤ 16 | `EFFECT_LIMIT` |
| 计划事件数量 | ≤ 256 | `INVALID_SCHEDULE` |
| 计划事件效果 | 1..16 | `INVALID_SCHEDULE` |
| 计划事件 label | 非空 ≤ 200 | `INVALID_SCHEDULE` |
| 计划事件 notice | 非空 ≤ 500 | `INVALID_SCHEDULE` |
| 计划事件 delayMinutes | 0..100000 | `INVALID_SCHEDULE` |
| 一次调度处理 | ≤ 64 个触发/跳过 | `SCHEDULE_LOOP` |
| initialFlags | ≤ 256 项 | `INVALID_SCHEDULE` |
| flag key / value | key 非空 ≤ 64；value ≤ 200 | `INVALID_SCHEDULE` / `INVALID_FLAGS` |
| Clock labels | 1..48 个；每个非空 ≤ 20；互不重复 | `INVALID_CLOCK` |
| Clock minutesPerUnit | 1..100000 | `INVALID_CLOCK` |
| ContentLevel 数量 | ≤ 10 | `INVALID_CONTENT_LEVEL` |
| ContentLevel label | 非空 ≤ 40 | `INVALID_CONTENT_LEVEL` |
| ContentLevel guideline | 非空 ≤ 2000 | `INVALID_CONTENT_LEVEL` |
| `GenerationPolicy.maxEntities` | 0..100 | `INVALID_GENERATION_POLICY` |
| 行动 title / publicSummary | 非空 ≤ 2000 | 无错误码 |
| RuleEvidence quote | 非空；安装期 ≤ 1200 | `INVALID_RULE_QUOTE` / `RULE_QUOTE_TOO_LARGE` |
| d20 卡牌 maxTargets | 1..6 | `INVALID_CARD` |
| d20 卡牌 slotLevel | null 或 ≥ 1 | `INVALID_CARD` |
| d20 CardEffect amount | -1000..1000 | `INVALID_CARD_EFFECT` |
| d20 优势 | -1..1 | `INVALID_ADVANTAGE` |
| d20 战斗者等级 | 1..30 | `INVALID_COMBATANT` |
| d20 属性值 | 1..30 | `INVALID_COMBATANT` |
| d20 熟练加值 | 0..10 | `INVALID_COMBATANT` |
| d20 AC | 0..60 | `INVALID_COMBATANT` |
| d20 技能级 | 0..2 | `INVALID_COMBATANT` |
| d20 一次提案的 d20 行动 | 1..6 | `D20_ACTIONS_REQUIRED` |

## 12. 最小结构示意（占位符，不是完整可导入包）

```json
{
  "formatVersion": 1,
  "moduleId": "my-module",
  "version": "1.0.0",
  "title": "示例标题",
  "author": "示例作者",
  "description": "示例简介",
  "engineApiVersion": 1,
  "rulesetId": "generic-d100-v1",
  "rulesetVersion": "1",
  "files": [
    { "path": "module/rules.json", "size": 0, "sha256": "..." },
    { "path": "module/sources/rules.md", "size": 0, "sha256": "..." },
    { "path": "module/world.json", "size": 0, "sha256": "..." }
  ]
}
```

`size` / `sha256` 必须换成真实值；`files` 的集合必须覆盖除 manifest 外的所有条目。`world.json` / `rules.json` 的完整字段按第 4–9 节填写。

## 附录 A. 运行期/协议可序列化类型（不是模组包输入）

以下类型由引擎创建或由 Agent 协议使用。作者不需要写进 ZIP；列出字段是为了阅读错误信息、日志或源码时能对上号。

| 类型 | 字段（默认值） | 用途 |
| --- | --- | --- |
| `CampaignRef` | `engineId`, `campaignId` | 战役引用 |
| `VersionRef` | `id`, `version`, `hash` | 版本引用（模组/规则） |
| `RoleContext` | `campaignId`, `audience`, `subjectId=null` | 读取角色的权限上下文 |
| `Claim` | `id`, `actorId`, `text`, `sourceEventSeq`, `visibility`, `supersedesId=null` | 主张记录 |
| `KnowledgeEdge` | `knowerId`, `factId`, `learnedAtSeq`, `method`, `belief` | NPC 知情边 |
| `EntityCreation` | `type`, `id`, `title`, `locationId`, `description`, `source` | 运行期生成实体 |
| `ScheduleStatus` | `state`, `fireAt=null` | 计划事件当前状态 |
| `ScheduledFiring` | `scheduleId`, `state`, `effects`, `fireAt=null` | 已记录的计划事件转移 |
| `ChapterMark` | `chapterId`, `title`, `ordinal` | 已记录的章节推进 |
| `ModuleDraft` | `moduleId`, `version`, `title`, `author`, `description`, `world`, `rules`, `sources` | 内部草稿构建；**不是 ZIP 导入格式** |
| `ValidatedModule` | `manifest`, `contentHash`, `world`, `rules`, `sources`, `originalFiles={}` | 校验后的内存表示 |
| `WorldState` | `locationId`, `resources`, `skills`, `npcs`, `items`, `facts`, `objectives`, `knowledge`, `generated=[]`, `gameTime=0`, `d20=null`, `flags={}`, `schedule={}` | 运行期世界状态 |
| `Campaign` | `id`, `title`, `module`, `rules`, `revision`, `lastEventSeq`, `world`, `allowGeneration`, `modelFingerprint`, `narratorFingerprint`, `updatedAt`, `nextTurnOrdinal=1`, `contentLevel=0`, `sceneHold=false`, `chapterId=null` | 战役状态 |
| `ActionProposal` | `actionType`, `targetIds`, `skill`, `difficulty`, `ruleRefs`, `conditions`, `successEffects`, `failureEffects`, `creations=[]`, `requiresConfirmation=false`, `publicSummary`, `temporaryRuling=false`, `d20=[]` | Agent 行动提案 |
| `FrozenPlan` | `baseRevision`, `moduleHash`, `rulesHash`, `modelFingerprint`, `proposal`, `evidence`, `planHash` | 冻结提案 |
| `DiceRoll` | `sides`, `value`, `threshold` | 百分骰结果 |
| `WorldEvent` | `eventVersion`, `campaignId`, `seq`, `turnId`, `effects`, `creations`, `dice`, `visibility`, `sources`, `rolls=[]`, `d20Changes=[]`, `scheduled=[]`, `chapters=[]` | 已提交事件 |
| `CommittedResult` | `turnId`, `eventSeq`, `success`, `dice`, `publicSummary`, `evidence`, `rolls=[]`, `notices=[]`, `chapters=[]` | 结算结果 |
| `ToolCall` | `invocationId`, `name`, `arguments` | Agent 工具调用 |
| `ToolResult` | `invocationId`, `toolSchemaVersion`, `argumentHash`, `revision`, `sourceRefs`, `output` | Agent 工具结果 |
| `AgentStep` | `version`, `kind`, `calls=null`, `proposal=null`, `text=null` | Agent 步骤 |
| `Usage` | `inputTokens`, `outputTokens` | Token 用量 |
| `ModelAttempt` | `id`, `purpose`, `fingerprint`, `status`, `usage=null` | 模型尝试记录 |
| `Turn` | `id`, `campaignId`, `ordinal`, `clientMessageId`, `playerText`, `baseRevision`, `phase`, `plan=null`, `result=null`, `narration=null`, `attempts=[]`, `tools=[]`, `error=null` | 回合 |
| `MemoryItem` | `id`, `campaignId`, `text`, `entityIds`, `fromSeq`, `toSeq`, `visibility`, `status`, `supersedesId=null`, `sourceTurnId=null`, `kind="EVENT"` | 记忆 |
| `CampaignHeader` | `ref`, `title`, `updatedAt` | 战役列表头 |
| `BackupManifestV2` | `archiveFormatVersion`, `engineId`, `engineVersion`, `eventSchemaVersion`, `module`, `rules`, `files` | 备份 manifest |
| `ContextPacket` | `purpose`, `system`, `input`, `maxOutputTokens` | 模型上下文 |
| `TextModelReply` | `text`, `usage` | 模型回复 |
| `D20State` | `combatants`, `round`, `roundLosses` | d20 运行状态 |
| `D20Action` | `kind`, `actorId`, `targetIds=[]`, `skill=null`, `attribute=null`, `dcTier=null`, `weaponId=null`, `cardId=null`, `slotLevel=null`, `advantage=0`, `advantageReason=null`, `restType=null` | d20 行动 |
| `RollRecord` | `actorId`, `purpose`, `expression`, `faces`, `modifier`, `total`, `target=null`, `success=null` | 骰子记录 |
| `D20Change` | `type`, `combatantId`, `key=null`, `delta=null`, `condition=null`, `value=null` | 已记录的 d20 变化 |
| `D20VisibleNpc` | `name`, `conditions` | 玩家可见 NPC 视图 |
| `TurnPhase` | 枚举 | `RECEIVED` / `GATHERING` / `PROPOSED` / `AWAITING_CONFIRMATION` / `READY` / `COMMITTED` / `NARRATING` / `COMPLETE` / `CANCELLED` |
