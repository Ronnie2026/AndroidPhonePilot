# AndroidPhonePilot 开发约定

- 开始新任务或恢复上下文时，先读 `docs/HANDOFF.md`，再按任务读取 `docs/ACTION_STANDARD.md`、`docs/ARCHITECTURE.md` 和真机验证记录。用当前 Git 和设备状态确认可能变化的信息。
- Kotlin / Android 原生项目。UI 参考 iOS 的紧凑分组风格，支持亮色、暗黑和跟随系统；首页展示统计，右上角齿轮进入设置，Token 启用开关位于该 Token 行右侧。
- 所有对外动作必须在 CommandDispatcher 中将 ActionSpec 与执行函数一起注册。MCP、HTTP、WS、发现接口及文档共用注册表，不另维护清单。遵循 `docs/ACTION_STANDARD.md`。
- Token 密钥、认证头、原始设备日志和私人设备标识不提交 Git。工作文件放 `work/`（已忽略）。维护开源致谢与实际依赖来源。
- 多设备连接时，ADB 必须显式选择序列号。读取 `adb devices -l`，不要依赖历史连接状态。不要在真机运行会清空日志的仪器测试。
- 用户确认 NX888J 无 Root；后续不能因更早聊天提过 Magisk 而假定已 Root。当前 Root 后端动作在该机不可用。不要擅自 Root、刷机、绕过锁屏或授予额外权限。
- 将构建、模拟器、特定真机和未验证行为分开报告。每次阶段完成后更新相关验证记录和交接文档。
- 涉及数据库表开发时，用户使用 MS SQL Server 2014；单独的前端原型默认 HTML。当前 Android 产品继续原生实现。
