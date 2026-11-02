# 长团运行时 v1

纯 Kotlin/JVM 模块。旧 `rpg-core` 的 DTO、序列化、规则和哈希不变。

公共契约位于 `contract/Models.kt` 与 `contract/Ports.kt`。格式版本：module=1、AgentStep=1、event=1、backup=2、engine=`longform-v1`。未知版本、工具、条件、效果和规则适配器直接拒绝。

`AgentStore.transaction` 只提供当前世界和单个 pending 回合。事件追加、当前投影和结果写入必须在同一事务；DAO 实现承担唯一性和回滚。历史、事件与首页分别分页，运行时不读取全战役历史。`nextTurnOrdinal` 与世界 `revision` 分开：无状态问答也占一个回合序号。

模型不能获得写库端口。`AgentCoordinator` 的模型调用发生在事务外，返回时校验 attempt、阶段与配置；取消之后的迟到结果不能提交。骰源只在 READY→COMMITTED 事务内调用。COMMITTED/NARRATING 恢复只补旁白，不重新结算。`interrupt` 只记录中断，`resume` 必须由用户触发。

每轮自动执行最多四次规划、八次只读工具、一次提交和一次旁白。每批最多三个工具；模型调用60秒、规划总时长180秒；工具单次8KiB、总32KiB；上下文默认12000估算token（包含输出预留）。超过预算保存当前阶段与错误，用户可处理尚未提交阶段。格式错误不自动付费修补。

公开旁白只收到 `PublicProjection`、公开结果与权限过滤记事；不会收到规划文本或 GM 工具记录。NPC 上下文只包含显式知情边与定向NPC事实，不复制玩家已发现事实。字符串秘密检测只检查明确秘密文本和ID，语言隐含剧透及叙述与自然语言规则的一致性仍需真实模型人工验收。

`ModuleZipImporter.validate(InputStream)` 无持久化副作用。通过预览后由 UI 调用 `ModuleCatalog.install` 激活。包只允许三个固定JSON与 `module/sources/<ASCII名>.md|txt`；限制实际读取字节，拒绝路径穿越、重复文件、符号链接、未知条目、非法UTF8、摘要/CRC错误、悬空引用及未知规则。保留原始文件文本与摘要，导出不因重新排版损坏依赖哈希。

`ModuleValidator.validateDraft(text)` 接受 `ModuleDraft`：moduleId、version、title、author、description、world、rules、sources。hash由程序计算；sourceHash可传空字符串。草稿须显式预览/接受，不能作为普通参考资料导入的自动后果。

外部原创样例位于 `examples/rpg-agent-v03/`。每份含6个地点、12名NPC和3章目标。`ExampleModules` 只负责可复现打包，不会自动安装。`DemoTextModelPort(module)` 是用户主动选择的离线演示；真实模型错误不会选择它。

验收用 `./gradlew -p rpg-agent test`。测试覆盖严格协议、多步查询、一次提交、中断续写、事务失败、1000权限组合、10000次事件重放与恶意ZIP。测试结果不等同真实模型语义质量或Android设备性能验收。

本次可安装测试版的边界：已提交行动形成带来源的长期记事；无状态回答、澄清和人物发言保存为 CONVERSATION 记事，绑定 sourceTurnId 并保留原回合正文。它们是发言/主张记录，不能直接成为世界事实。NPC 可随主行动更新在场状态、信任等关系及显式知情边；尚不根据离场NPC目标自行安排定时行动。章节摘要任务与记忆原文分离，当前不提供自动付费摘要生成。规则检索的Android FTS召回、手机性能与真实模型连续长团仍须对应测试记录，不能用JVM样例取代。
