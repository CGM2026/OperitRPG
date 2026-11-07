# 07 纸面界面、简化连接与更新

依据：用户的另一个项目"纸牌占卜 Agent"已确认的外观规范与其 Android Compose 组件。参考副本（只读，仓库外）：`D:\AI_workspace\Vibe coding\OperitRPG\_reference\cards-ui\`（`已确认外观规范.md`、`CardsTheme.kt`、`CardsPaperComponents.kt`、`CardsPage.kt`、`CardsSettingsPanel.kt`、`CardsAppearancePanel.kt`、`CardsUpdates.kt`）。头像不在本步范围内。

## 已拍板

- 只用纸色浅底，不做深色模式（删除现有深色配色与 `isSystemInDarkTheme` 切换）。
- 六个主色在 设置 → 外观 可选，默认深鼠尾草：鼠尾草 `#596F60`、石板蓝 `#586E76`、烟玫瑰 `#825F65`、灰茶褐 `#786448`、暮丁香 `#716178`、陶土棕 `#865F4F`；主色上的文字一律 `#F3EEE4`。
- 纸面色：纸 `#F6F2E9`，墨（正文）`#343936`，次要 `#73766F`，线 `#DFD9CD`，填充（输入框/助手气泡/卡片底）`#EEE9DF`，错误 `#925A54`。
- 用户气泡、实心主按钮、发送按钮、整行选中底色 = 当前主色（不透明度 1）；其余辅助选中用主色的柔底（主色 18% 叠在纸色上）配墨色字。
- 字号放大（用户要求）：正文 18sp / 行高 29sp；主持叙述 18sp 衬线 / 行高 31sp；行项目标题 17sp；次要文字 14sp；页面/弹窗标题 20sp SemiBold；最小 12sp（仅标签类）。
- 组件几何：居中弹窗圆角 14dp、内边距 18dp、最大宽 560dp、遮罩 `#1F221E` 32%；按钮圆角 8dp、最小高 48dp；输入框圆角 8dp、最小高 50dp、底色为填充色、无描边；触控区 ≥ 44dp；页面结构"左返回 + 标题（60dp 高）+ 一列 60dp 行项目（图标 22dp、标题、右箭头）"。
- 模型协议：默认 OpenAI 兼容；Anthropic、Gemini、Responses 收进"更多"。
- 设置里加"版本更新"页，按参考规范的五态：尚未检查更新 / 正在检查更新… / 已是最新版本 / 发现可用更新 / 检查失败请重试；版本号大字居中加粗，检查按钮为主色实心；发现新版本弹居中确认窗（左"暂不下载"、右"下载"，等宽）；关闭、点遮罩、返回都不构成同意。复用现有 `rpg/update` 的检查与下载逻辑。
- 界面不放解释性段落；状态只用一行短句。

## 任务拆分

| 任务 | 内容 | 允许修改 |
|---|---|---|
| A1 设计系统 | 新建 `rpg/ui/paper/`：`PaperTheme.kt`（色值、六主色 Tone、CompositionLocal、字号常量）、`PaperComponents.kt`（PaperSheet 居中弹窗、PaperPage 页面、PaperField、PaperAction、PaperMenu/MenuAction、PaperRow 行项目、ChoiceMark），按参考组件移植并改名；`D20Theme` 改为只包 PaperTheme（删深色）；主色选择持久化到 SharedPreferences `rpg-agent-ui` 的 `tone` 键 | `rpg/ui/paper/**`、`rpg/ui/theme/**`、`ui/main/MainActivity.kt` |
| B1 连接测试（模型层） | `rpg/model/` 增加 `listModels(config)`（OpenAI 兼容 GET `{base}/models`，解析 `data[].id`）与 `verify(config, model)`（发 1 条 `ping`、max_tokens 8 的请求，成功即通过）；错误映射为用户可读短句：401/403"密钥无效"、404"地址不对"、429"额度或频率受限"、5xx"服务端错误"、超时"连接超时"、解析失败"返回格式不是 OpenAI 兼容"，均带 HTTP 码；其他协议 `listModels` 不支持时返回明确的"该协议不支持列出模型，请手动填写"异常类型而非空列表；单元测试用假的 HTTP 层 | `rpg/model/**`、`app/src/test/.../rpg/model/**` |
| A2 页面换装 | 首页、对话页、人物卡、头像选择、长按菜单、对话框全部改用 paper 组件与新字号；对话页输入栏按参考 `CardsComposer` 的形态（填充色圆角 14 输入区，下方一行：左 `+`、右发送圆钮） | `rpg/ui/home/**`、`rpg/ui/play/**` |
| B2 设置与连接界面 | 新设置首页（行项目：模型连接、外观、模组库、版本更新）；模型连接页：地址 + 密钥（遮罩/显示/清除）→"连接"拉模型列表 → 点选模型 →"验证并保存"；已保存配置每项有"测试"；"更多"里切协议与手填模型名；外观页六色选择；版本更新页五态；删除旧 `RpgModelSettingsScreen` 中的说明段落与旧布局 | `rpg/ui/settings/**`（新）、`rpg/ui/RpgModelSettingsScreen.kt`（删除/替换）、`rpg/update/**`（只改 UI 部分） |
| C 导入流程 | 首页 `+` → 系统文件选择 → 一张卡片（模组标题、简介、角色单选、模型选择）→"开始"；失败一行错误；模组库移到设置 | `rpg/agent/ui/**` |

顺序：A1 与 B1 并行 → A2、B2、C 并行（都依赖 A1；B2 依赖 B1）。

## 验收

CI 绿；B1 单元测试覆盖每个错误映射；A1 单元测试：六个主色与 `#F3EEE4` 的对比度 ≥ 4.5（用 luminance 计算）。
