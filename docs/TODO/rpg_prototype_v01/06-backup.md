# 完整存档导入导出

原有 Operit 聊天备份不能承载跑团事件。新增独立 RpgBackupService，以 GameStore 的事务快照导出，以一次 create 写入新战役，不覆盖任何已有战役。

固定 ZIP 内容为 manifest.json、campaign.json、turns.jsonl、events.jsonl 和 narrations.jsonl。manifest 记录其余四文件的字节数及 SHA-256；不包含自引用 hash。战役头不重复容纳回合和事件，旁白单独保存。所有配置替换为 UNBOUND，请求尝试和 attemptId 不导出。

校验覆盖 ZIP 中央目录、CRC、固定路径、重复条目、10 MiB 压缩/20 MiB 展开限制、严格 UTF-8、版本/hash、JSON 结构、记录 ID、ordinal/sequence、外键、计划 hash、每回合检定和事件重放后的完整状态。仅 COMPLETE/CANCELLED 回合可通过。hash 为完整性检查，不提供来源身份认证。

预览不写库。确认导入再次校验，生成新 campaign/turn/event/clientMessage ID，重映射事件及状态/检定内的线索来源引用；模型保持 UNBOUND。UI 使用系统文件选择器并明确完整档案含模组信息。

测试位于 app/src/test/java/com/ai/assistance/operit/rpg/backup。纯 JVM 临时构建入口只增加 backup 的源文件目录，不修改核心模块永久配置。覆盖往返、ID 重映射、无配置泄漏、损坏/恶意包、重算 hash 后伪造投影/检定/事件、未完成回合、预览再验证和创建失败。

2026-11-02 最终复跑：BUILD SUCCESSFUL，9 项测试，0 失败、0 错误；XML 已复制到交付 outputs/operit-rpg-build/backup-tests。使用 JDK 21、Gradle 8.13，关闭 Kotlin 增量缓存并在进程内编译，避免多执行者共享缓存争用。

[DONE]
