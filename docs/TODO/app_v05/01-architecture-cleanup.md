# 01 架构整理

## 目标

App 只保留 `rpg-agent` 这一条引擎路径，删掉旧引擎（`rpg-core`）的全部入口与 App 内模组草稿功能，并把 App 代码按层整理，为后续 UI 重做铺好骨架。本步**不做视觉重设计**，界面可暂时保持现状，只要能编译、能跑通"导入模组 → 新建战役 → 对话"。

## 删除

1. 旧引擎 UI 与数据：`rpg/ui/RpgScreen.kt`、`rpg/ui/RpgUiContract.kt`、`rpg/integration/RpgRoute.kt`、`rpg/data/RpgDatabase.kt`、`rpg/backup/RpgBackupService.kt`，以及只服务它们的测试（`RpgScreenTest`、`RoomGameStoreTest`、`RpgBackupServiceTest`、`LegacyBackupCompatibilityTest`）。
2. `D20Route` 中的 legacy 分支、`d20-navigation`/`rpg-ui` SharedPreferences 判断、`onOpenLegacy` 回调；`AgentViewModel` 中读取旧引擎条目（`legacy`）的逻辑。
3. 模组草稿：`rpg/model/AgentDraftService.kt`，`AgentViewModel` 的 `draft*` 相关状态与方法、`rpg-agent-module-draft.json` 文件读写，`AgentScreen` 中的草稿对话框与入口，`AgentUiContract` 中相应字段。
4. `settings.gradle.kts` 去掉 `:rpg-core`；`app/build.gradle.kts` 去掉对它的依赖。`rpg-core/` 目录整体删除。CI workflow 中 rpg-core 测试步骤同步删除。

## 迁移

`rpg/model/` 目前从 `rpg-core` 借用 `ModelRequest`、`ModelReply`、`Usage`、`ModelConfiguration`、`sha256`。把这些类型按原语义搬到 App 的 `rpg/model/` 包内（只搬实际用到的部分，不复制整个旧引擎），保持模型网关行为与现有测试不变。

## 分层（包结构）

```
com.ai.assistance.operit.rpg
├── data        Room 数据库、DAO、实体、仓库实现（原 agent/data）
├── domain      战役、分组、模组库的仓库接口与用例（本步只建 Campaign/Module 两个接口，把 ViewModel 里直接操作存储的代码移进来）
├── model       模型网关与配置（原 model，去掉 rpg-core 依赖）
├── ui
│   ├── nav     导航（单 Activity + Compose Navigation，类型安全路由：Home、Play(campaignId)、Settings、ModelSettings、ModuleLibrary）
│   ├── home    首页（本步沿用现有列表界面）
│   ├── play    对话页（本步沿用现有界面）
│   └── settings
└── update      保持不动
```

- 拆 `AgentViewModel`：按页面拆成 `HomeViewModel`、`PlayViewModel`、`SettingsViewModel`（模组库可并入 Settings）。每个 ViewModel 只依赖 domain 接口。
- 数据库：不兼容 0.3，`AgentDatabase` 版本重置为 1，删除 `AGENT_MIGRATION_1_2` 与旧 schema 文件，数据库文件名改为新名称，确保旧安装升级后不会读旧库（旧库文件留在磁盘不处理）。导出新的 schema JSON 并提交（`app/schemas/`）。
- 本步不新增"分组"表；分组在 03 步实现。

## 约束

- 遵守仓库 AGENTS.md：不写 fallback/兜底；本地不跑构建；文档同步更新（删掉旧引擎相关 docs 的引用时保留历史 TODO 文档本身）。
- 不改动 `rpg-agent` 模块的引擎行为；其测试必须全部通过。
- 提交前自查：`grep -rn "rpg.core\|legacy\|AgentDraft" app/src` 应无结果（`legacy` 若出现在无关语义中需说明）。

## 验收

- CI 绿：rpg-agent 测试、App 单元测试、`:app:compileDebugKotlin`。
- `app/schemas/` 中有新版本 1 的 schema。
- 手动路径（由用户在手机上验证）：导入模组 zip → 新建战役 → 发送一条行动并得到叙述。
