# 模组包导入前自检清单

按 `ModuleZipImporter.validate` 与两个适配器的 `validateModule` 的**实际执行顺序**排列。任何一项失败都会中止导入；不要带着“应该能过”的侥幸打包。

记号说明：

- “无错误码”指 Kotlin `require(...)` 没有 message，异常类型为 `IllegalArgumentException`，消息类似 `Failed requirement.`。
- 序列化错误（未知字段、类型错误、枚举错误、缺字段）来自 kotlinx.serialization，没有本项目自定义错误码。
- `ModuleZipImporter.validate` 通过后，导入 UI 才会显示预览；用户确认后才进入安装。安装期的检查单列在第 K 节。

## A. 打包前人工检查（不产生错误码，但会决定后面是否失败）

- [ ] 只使用真实提供的原始资料，没有编造原文、数字、人物或因果。
- [ ] 公开 source 与 GM source 分开；秘密不在公开 source 里。
- [ ] 所有 `RuleEvidence.quote` 是从最终 source 文件复制出的逐字子串，标点/空格/换行/全半角一致。
- [ ] 每条证据的 `source.sourceHash` 与 source 文件真实 SHA-256 一致。
- [ ] 所有实体 id 以 `${moduleId}:` 开头，跨 locations/npcs/items/facts/objectives/characters/actions/schedule 唯一。
- [ ] 所有 effect 的 `source` 是某条证据 `source` 的完整副本（sourceId/sourceHash/anchor/authority 全部一致）。
- [ ] 玩家可见字段（行动标题/公开摘要、地点描述、NPC 名、事实、目标、章节标题、内容分档标签、`notice`）没有秘密或剧透。
- [ ] `module/rules.json` 的 `adapterId/adapterVersion` 与 manifest 的 `rulesetId/rulesetVersion` 一致。
- [ ] 修改任何文件后已重算 `manifest.files` 的 size/sha256，没有复用旧 manifest。

## B. ZIP 归档层（`ModuleZipImporter.validate` 开头）

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| B1 | 整个输入流 ≤ 20 MiB | `ZIP_SIZE_LIMIT` |
| B2 | 没有符号链接条目 | `ZIP_SYMLINK_REJECTED` |
| B3 | 条目数 ≤ 2000 | `ZIP_ENTRY_LIMIT` |
| B4 | 每个条目不是目录，且路径通过 `safePath`（无绝对路径、`\`、`:`、空段、`.`、`..`；只允许三个 JSON 与 `module/sources/*.md` / `*.txt`） | `ZIP_PATH_REJECTED` |
| B5 | 路径没有重复 | `ZIP_DUPLICATE_PATH` |
| B6 | 单个条目解压后 ≤ 5 MiB | `ZIP_SIZE_LIMIT` |
| B7 | 所有条目解压后总计 ≤ 50 MiB | `ZIP_EXPANDED_LIMIT` |
| B8 | CRC 与条目内容一致 | `ZipException`（无自定义错误码） |
| B9 | 必须存在 `module/manifest.json`、`module/world.json`、`module/rules.json` | `MODULE_FILE_MISSING` |
| B10 | 每个条目字节都是合法 UTF-8 | `MalformedInputException` / `CharacterCodingException`（无自定义错误码） |

> 目录条目会被 B4 拒绝。打包时不要用“包含空目录”的方式生成 ZIP。

## C. manifest 与文件哈希

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| C1 | `module/manifest.json` 是严格 JSON：≤ 262144 字节、深度 ≤ 64、无重复 key、无未知字段、类型正确 | `JSON_LIMIT` / `JSON_DEPTH_LIMIT` / `DUPLICATE_JSON_KEY` / 序列化异常 |
| C2 | `formatVersion == 1` 且 `engineApiVersion == 1` | `UNSUPPORTED_MODULE_VERSION` |
| C3 | `moduleId` 匹配 `[a-z][a-z0-9-]{2,63}` | `INVALID_MODULE_ID` |
| C4 | `version` 匹配 `[0-9]+\.[0-9]+\.[0-9]+` | `INVALID_MODULE_ID` |
| C5 | `manifest.files` 中的 path 互不重复 | 无错误码（`IllegalArgumentException`） |
| C6 | `manifest.files` 的 path 集合正好等于 ZIP 中除 manifest 外的全部 path | `MANIFEST_FILE_MISMATCH` |
| C7 | 每个 `FileDigest.size` 等于实际字节数 | `MODULE_HASH_MISMATCH` |
| C8 | 每个 `FileDigest.sha256` 等于实际字节的 SHA-256（小写 hex） | `MODULE_HASH_MISMATCH` |
| C9 | `module/world.json` 严格解析通过（同 C1 的大小/深度/重复 key/未知字段/类型规则） | `JSON_LIMIT` / `JSON_DEPTH_LIMIT` / `DUPLICATE_JSON_KEY` / 序列化异常 |
| C10 | `module/rules.json` 严格解析通过 | 同上 |
| C11 | `manifest.rulesetId == rules.adapterId` | `RULE_VERSION_MISMATCH` |
| C12 | `manifest.rulesetVersion == rules.adapterVersion` | `RULE_VERSION_MISMATCH` |
| C13 | manifest 可被规范化并计算内部 contentHash | 无错误码（正常不会失败） |

## D. 证据与 source

导入器按 `rules.evidence` 的顺序逐条检查：

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| D1 | `evidence.visibility` 通过 `validVisibility`（PUBLIC/GM 的 subjectId 必须 null；NPC/FACTION 的 subjectId 非空） | `INVALID_VISIBILITY` |
| D2 | `evidence.source.sourceId` 对应 ZIP 内存在的文件 | `SOURCE_HASH_MISMATCH` |
| D3 | 该文件 SHA-256 等于 `evidence.source.sourceHash` | `SOURCE_HASH_MISMATCH` |
| D4 | `evidence.source.anchor` 非空 | `INVALID_RULE_QUOTE` |
| D5 | `evidence.quote` 非空 | `INVALID_RULE_QUOTE` |
| D6 | `evidence.quote` 是对应 source 解码后文本的逐字子串 | `INVALID_RULE_QUOTE` |

## E. 适配器选择与 `validateModule` 前置校验

先由 `RuleAdapters.forRules(rules.adapterId, rules.adapterVersion)` 选择适配器：

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| E0 | 组合必须是 `generic-d100-v1/1` 或 `wuxia-d20-v1/1` | `UNKNOWN_RULE_ADAPTER` |

### E.1 `generic-d100-v1` 的顺序

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| E-G1 | `rules.adapterId/adapterVersion` 与适配器完全匹配 | `UNKNOWN_RULE_ADAPTER` |
| E-G2 | `contentLevels` 为空时 `defaultContentLevel==0`；非空时 ≤10、label 非空 ≤40、guideline 非空 ≤2000、default 在范围内 | `INVALID_CONTENT_LEVEL` |
| E-G3 | `rules.allowedEffects` 是 generic 效果集合的子集 | `UNKNOWN_EFFECT` |
| E-G4 | locations/npcs/items/facts/objectives/characters/actions/schedule 的 id 全局唯一且都以 `${moduleId}:` 开头 | `INVALID_ENTITY_ID` |
| E-G5 | `startLocationId` 存在于 locations | `MISSING_START_LOCATION` |
| E-G6 | characters 非空 | `MISSING_CHARACTER` |
| E-G7 | 每个 location 的每个 exit 都指向已有 location | `DANGLING_EXIT` |
| E-G8 | 每个 NPC 的 `locationId` 都指向已有 location | `DANGLING_NPC_LOCATION` |
| E-G9 | `difficulties` 非空，且每个 `ResourceBounds.min <= max` | 无错误码（`IllegalArgumentException`） |
| E-G10 | 每个 character 的 `skills` key 都在 `rules.skills` 中，值在 `0..100` | `INVALID_SKILL` |
| E-G11 | 每个 `Fact.visibility` 合法 | `INVALID_VISIBILITY` |
| E-G12 | `policy.maxEntities` 在 `0..100`，`allowedEntityTypes` 只含 `npc` / `location_detail` | `INVALID_GENERATION_POLICY` |
| E-G13 | `policy.lockedFactIds` 每一项都指向 `locked=true` 的事实 | 无错误码（`IllegalArgumentException`） |

之后按 `world.actions` 顺序执行 F 节，再进入 G/H/I 节。

### E.2 `wuxia-d20-v1` 的顺序

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| E-W1 | `rules.adapterId/adapterVersion` 与适配器完全匹配 | `UNKNOWN_RULE_ADAPTER` |
| E-W2 | `contentLevels` 规则同 E-G2 | `INVALID_CONTENT_LEVEL` |
| E-W3 | `rules.d20` 非空 | `MISSING_D20_RULES` |
| E-W4 | `rules.allowedEffects` 是 wuxia 叙事效果集合的子集 | `UNKNOWN_EFFECT` |
| E-W5 | 实体 id 唯一且带前缀（同 E-G4） | `INVALID_ENTITY_ID` |
| E-W6 | `startLocationId` 存在 | `MISSING_START_LOCATION` |
| E-W7 | characters 非空 | `MISSING_CHARACTER` |
| E-W8 | exits 目标存在 | `DANGLING_EXIT` |
| E-W9 | NPC 地点存在 | `DANGLING_NPC_LOCATION` |
| E-W10 | 每个 `Fact.visibility` 合法 | `INVALID_VISIBILITY` |
| E-W11 | 生成策略合法 | `INVALID_GENERATION_POLICY` |
| E-W12 | `lockedFactIds` 指向锁定事实 | 无错误码（`IllegalArgumentException`） |
| E-W13 | `D20Rules` 自身合法（属性、技能、DC、专注属性、休息、战败池、池、计数器、状态、卡牌、卡牌效果） | `INVALID_D20_RULES` / `INVALID_POOL` / `INVALID_COUNTER` / `INVALID_CONDITION` / `INVALID_CARD` / `INVALID_CARD_EFFECT` / `UNKNOWN_CONDITION` / `UNKNOWN_POOL` / `UNKNOWN_COUNTER` / `INVALID_DURATION` / `INVALID_DICE` |
| E-W14 | 所有 `d20Combatants.id` 互不重复，且都是某个 character 或 NPC 的 id | `INVALID_COMBATANT` |
| E-W15 | 每个 character 都有对应战斗者 | `MISSING_COMBATANT` |
| E-W16 | 每个战斗者字段合法（name/level/scores/proficiency/saves/skillRanks/AC/pools/slots/counters/cards/weapons/conditions/concentration） | `INVALID_COMBATANT` / `INVALID_DICE` |

之后按 `world.actions` 顺序执行 F 节，再进入 G/H/I 节。

> `wuxia-d20-v1` **不校验** `CharacterTemplate.skills`、`ModuleRules.skills`、`difficulties`、`resourceBounds`（这些字段仍必须存在）。`generic-d100-v1` **不使用** `rules.d20`。

## F. 每个 ActionDefinition 的预校验（按 `world.actions` 顺序）

模块加载会把每个行动当作一次提案，在“行动地点”执行 `validateProposal`。通用顺序：

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| F0 | **wuxia only**：`check != null` 与 `actionType=="d20"` 必须同时成立或同时不成立（此检查在 `validateProposal` 之前） | `ACTION_CHECK_MISMATCH` |
| F1 | `actionType` 在适配器允许集合中（generic：investigate/move/negotiate/use_item/wait/skill_check） | `UNKNOWN_ACTION` |
| F2 | `title` 作为 `publicSummary` 非空且 ≤ 2000 字符 | 无错误码 |
| F3 | **generic**：`difficulty` 是 `rules.difficulties` 的 key | `UNKNOWN_DIFFICULTY` |
| F4 | **generic**：`skill` 为 null 或存在于初始 `world.skills` | `UNKNOWN_SKILL` |
| F5 | **generic**：`skill_check` 必须有非空 skill；move/wait/use_item/investigate/negotiate 可以没有 | `SKILL_REQUIRED` |
| F6 | **wuxia**：`skill` 必须为 null | `SKILL_NOT_SUPPORTED` |
| F7 | `ruleId` 必须有对应证据；`ruleRefs` 非空 | `RULE_NOT_FOUND` |
| F8 | 不是临时裁定（模块行动没有 `temporaryRuling`） | `RULING_CONFIRMATION_REQUIRED` |
| F9 | 所有 `targetIds` 都存在于世界（location/npc/item/fact/objective/generated 或 `player`/`world`） | `UNKNOWN_TARGET` |
| F10 | **generic**：investigate/negotiate/use_item/skill_check 的目标 NPC 不能在其他地点 | `TARGET_NOT_PRESENT` |
| F11 | 条件节点总数 ≤ 64 | `CONDITION_LIMIT` |
| F12 | 条件树每个节点类型/参数合法；深度 > 8 报错 | `UNKNOWN_CONDITION` / `CONDITION_DEPTH`（以及各类型无错误码失败） |
| F13 | 成功效果 ≤ 16、失败效果 ≤ 16 | `EFFECT_LIMIT` |
| F14 | 每个效果类型在 `allowedEffects` 且属于适配器允许集合 | `UNKNOWN_EFFECT` |
| F15 | 每个效果 `source.authority` 合法，且非临时来源必须匹配一条证据的完整 source | `INVALID_EFFECT_SOURCE` |
| F16 | 效果的参数合法（move 可达、resource 边界、NPC/物品在场、knowledge 的 fact 为 PUBLIC 等） | `UNREACHABLE` / `NPC_CANNOT_KNOW` 等（部分无错误码） |
| F17 | 效果来源必须有证据（与 F15 有重叠） | `EFFECT_EVIDENCE_MISSING` |
| F18 | 模块行动不能使用临时裁定效果 | `RULING_CONFIRMATION_REQUIRED` |
| F19 | 揭示 locked fact 时，必须存在一个模组行动包含同一效果、其条件被当前行动覆盖、目标被当前行动包含 | `LOCKED_FACT` |
| F20 | 模块行动没有 creations；运行期 creations 才走生成检查 | `GENERATION_DISABLED` / `GENERATION_LIMIT` / `GENERATION_FORBIDDEN` |
| F21 | **wuxia**：d20 行动必须带 1–6 个 D20Action，非 d20 行动必须为空 | `D20_ACTIONS_REQUIRED` / `D20_NOT_ALLOWED` |
| F22 | **wuxia**：逐个 D20Action 校验（kind/actor/目标在场/条件/优势/武器/卡牌/槽/计数器/时机/休息） | `UNKNOWN_D20_KIND` / `UNKNOWN_ACTOR` / `ACTOR_NOT_PRESENT` / `UNKNOWN_TARGET` / `TARGET_NOT_PRESENT` / `DUPLICATE_TARGET` / `ACTOR_BLOCKED` / `ACTOR_DEFEATED` / `INVALID_ADVANTAGE` / `ADVANTAGE_REASON_REQUIRED` / `UNKNOWN_DC_TIER` / `SAVE_NOT_ALLOWED` / `UNKNOWN_WEAPON` / `UNKNOWN_CARD` / `CARD_NOT_OWNED` / `TARGET_COUNT` / `SLOT_LEVEL_INVALID` / `SLOT_EMPTY` / `COUNTER_EMPTY` / `TARGET_CONDITION_REQUIRED` / `ACTOR_CONDITION_FORBIDDEN` / `TIMING_ALREADY_USED` / `BONUS_SPELL_RULE` / `REST_NOT_ALLOWED` / `INVALID_REST_TYPE` / `INVALID_D20_ACTION` |

## G. 章节（`validateChapters`）

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| G1 | chapters 为空则跳过 | — |
| G2 | 所有 chapter id 非空且互不重复，所有 title 非空 | `INVALID_CHAPTER` |
| G3 | 第一章 `enterWhen` 为空；第二章起每章 `enterWhen` 非空 | `INVALID_CHAPTER` |
| G4 | 每章 `enterWhen` 的每个条件按初始世界合法（类型/参数；此路径不调用 `count`，没有 64 节点/深度上限） | `UNKNOWN_CONDITION` 及各类型失败（部分无错误码） |

## H. 计划事件与标记（`Scheduler.validateModule`）

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| H1 | `schedule.size <= 256` | `INVALID_SCHEDULE` |
| H2 | `initialFlags.size <= 256`，且每项 key 非空 ≤64、value ≤200 | `INVALID_SCHEDULE` |
| H3 | `clock` 非空时：labels 1–48、互不重复、非空 ≤20；startLabel ∈ labels；minutesPerUnit 1–100000 | `INVALID_CLOCK` |
| H4 | 每个事件：label 非空 ≤200；effects 1–16；delayMinutes 0–100000 | `INVALID_SCHEDULE` |
| H5 | notice 为 null 或非空 ≤500 | `INVALID_SCHEDULE` |
| H6 | `atMinute != null` 时 ≥0 且 delayMinutes==0；`atMinute == null` 时 conditions 非空 | `INVALID_SCHEDULE` |
| H7 | `conditions + cancelIf` 节点总数 ≤64 | `CONDITION_LIMIT` |
| H8 | 每个条件按初始世界合法 | `UNKNOWN_CONDITION` / `CONDITION_DEPTH` 等 |
| H9 | 每个效果类型属于 `Scheduler.effectKinds` | `UNKNOWN_EFFECT` |
| H10 | 每个效果 `authority=="module_rule"` 且匹配一条证据 source | `INVALID_EFFECT_SOURCE` |
| H11 | 每个效果参数合法（npc_move/npc_status/transfer_item/knowledge/objective/set_flag/reveal_fact） | `INVALID_SCHEDULE_EFFECT` |

## I. 初始状态校验（`validateState`）

### I.1 两个适配器共有（顺序相同）

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| I1 | schedule 的 key 集合正好等于模块声明的 schedule id 集合 | `INVALID_SCHEDULE_STATE` |
| I2 | 每个 schedule 状态合法；`armed` 必须有 `fireAt` | `INVALID_SCHEDULE_STATE` |
| I3 | world.flags ≤256 且 key/value 合法 | `INVALID_FLAGS` |
| I4 | 当前 `locationId` 存在于 locations | 无错误码 |
| I6 | `gameTime >= 0`；generated 数量 ≤ `policy.maxEntities` | 无错误码 |
| I7 | knowledge 边的事实存在，knower 是 `player` 或已有 NPC | 无错误码 |

### I.1g 仅 `generic-d100-v1`（位于 I4 与 I6 之间）

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| I5 | 每个 resource 有 bounds 且值在界内（初始资源来自第一个角色） | 无错误码 / `NoSuchElementException` |

### I.2 仅 `wuxia-d20-v1`

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| I8 | `world.d20` 非空 | `MISSING_D20_STATE` |
| I9 | round ≥0，roundLosses 全部 ≥0 | `INVALID_D20_STATE` |
| I10 | 每个 combatant 的 key 等于其 id，且 id 是 PC 或已有 NPC | `INVALID_COMBATANT` |
| I11 | 当前在场的 PC 数量正好为 1 | `INVALID_COMBATANT` |
| I12 | 每个在场战斗者字段合法 | `INVALID_COMBATANT` / `INVALID_DICE` |

## J. 导入器通过后仍可能失败的情形

- 预览阶段通过，但用户在安装前修改了 ZIP：不会重新校验，安装可能失败或装入旧内容。不要这样做。
- 同一个 `moduleId + version` 已安装且内容哈希不同：安装报 `MODULE_VERSION_CONFLICT`。请提升 `version`。
- 证据 quote 超过 1200 字符：导入器不拦，安装报 `RULE_QUOTE_TOO_LARGE`。
- sourceId 指向非 `module/sources/` 文件：导入器可能通过，安装从 sources 映射取值时 `NoSuchElementException`。

## K. 安装落库阶段（`RoomModuleCatalog.install` + `validateStoredModule`）

这些检查发生在用户确认导入之后，不属于 `ModuleZipImporter.validate`，但仍会导致“导入失败”：

| # | 检查 | 失败错误码 |
| --- | --- | --- |
| K1 | 重新计算的 contentHash 等于 `ValidatedModule.contentHash` | `MODULE_CONTENT_HASH_MISMATCH` |
| K2 | manifest 文件集合与 `originalFiles` 完全一致且数量一致 | `MODULE_FILES_MISSING` |
| K3 | 每个原始文件 size/sha256 与 manifest 一致 | `MODULE_SOURCE_HASH_MISMATCH` |
| K4 | 重新解码 world/rules 等于内存对象 | `MODULE_DEFINITION_MISMATCH` |
| K5 | `originalFiles` 中 `module/sources/` 部分等于 `sources` | 无错误码 |
| K6 | 每条证据的 source 存在、hash 匹配、quote 是子串且非空 | 无错误码 |
| K7 | 再次执行 `validateModule` | 同第 E–I 节 |
| K8 | 同 id+version 已存在且 hash 相同 | 幂等返回，不报错 |
| K9 | 同 id+version 已存在且 hash 不同 | `MODULE_VERSION_CONFLICT` |
| K10 | 每条证据 quote 长度 ≤1200 | `RULE_QUOTE_TOO_LARGE` |
| K11 | 证据 sourceId 必须能从 sources 映射取到 | `NoSuchElementException`（无自定义错误码） |

## L. 最终机械自检（建议在打包脚本里自动做）

1. `unzip -l pack.zip`，确认没有目录条目、没有额外文件、路径全部符合第 2 节。
2. 对每个非 manifest 文件算 `sha256` 和字节数，与 `manifest.files` 逐项比对。
3. 对每个 source 算 SHA-256，与每条 evidence 的 `source.sourceHash` 比对。
4. 用严格 JSON 解析器（拒绝重复 key/尾逗号/注释）解析三个 JSON。
5. 对每条 evidence 执行 `source_text.contains(quote)`。
6. 检查所有实体 id 前缀与全局唯一性。
7. 至少用一个真实 App 版本执行一次导入预览；预览失败就按错误码回溯，不要绕过校验。
