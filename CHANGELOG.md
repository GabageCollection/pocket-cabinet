# 修改记录（审查问题 → 修复 → 验证）

测试基线：`./gradlew :app:testDebugUnitTest` 52 个用例全部通过；
`:app:assembleDebug` / `:app:assembleRelease` 均构建成功。

## 二、库存与调酒流程

| 问题（审查线索） | 修复 | 验证 |
| --- | --- | --- |
| `confirm()` 用 `emptyMap()` 丢弃选瓶；`stPlanSubs()` 自动确认全部替代 | 调酒改为草稿驱动：详情页把杯数、选瓶、已确认替代写入持久化 `MixDraft`，逐步调酒以草稿 ID 串联；提交时使用草稿中的选择 | `EngineTest.commitHonorsUserPickedBottle`（指定 B 瓶实际先扣 B 瓶）、`unconfirmedSubNeverExecuted`（未确认替代不动库存） |
| 替代只存布尔，无法区分方案 | `chosenSubs` 改为 原材料 ID → 替代材料 ID 的明确映射；替代弹层列出全部可用方案（比例/风味/适用方法/可调制杯数） | `EngineTest.substitutionDeductsSubstitute`、详情页 UI |
| 无草稿、无恢复、「进度已保存」为假提示 | 新增 `mix_drafts` 表；开始调酒即建草稿，切步骤/计时启停实时落库；发现页「继续上次调酒」卡片；退出按钮先保存成功再返回，发现页 Snackbar 才提示「进度已保存」 | `RoomDbTest.draftSurvivesReopen`（关闭数据库重开后草稿完整恢复） |
| 计时靠协程每秒减一 | 计时以 `timerEndAt`（到期时刻）为基准持久化，进程重建/旋转后按基准恢复剩余时间 | `draftSurvivesReopen` 校验 timerRunning/timerEndAt 字段 |
| 库存/流水精度：扣减保留 1 位小数、delta 与实际变化不一致 | 统一 `Qty.round`（0.001 单位）：扣减、恢复、盘点、入库、备份全部经过同一规整；`deduct()` 返回实际应用量，流水 delta 即实际变化 | `EngineTest.fractionalQtyPrecisionRoundTrip`（0.125 个多次扣减+撤销后库存与流水一致） |
| 每种材料独立判断「够用」，重复材料/附加材料重复占用库存 | `planPour` 先按目标材料聚合全部需求（重复行、替代、替代搭配），再一次性分瓶 | `EngineTest.duplicateIngredientAggregated`、`extraIngredientShortageNoPartialCommit` |
| 「部分扣减后 return null」可正常提交事务 | 计划在写入前完整计算；执行期失败只可能抛异常 → Room 回滚；不存在部分扣减后正常返回的路径 | `RoomDbTest.midCommitExceptionRollsBackEverything`（真实库：第二次扣减抛异常后库存/流水/记录零变化） |
| 确认页计划与实际提交可能不一致 | 提交时以事务内实时库存重算计划并比对指纹，不一致返回 `PlanChanged`，界面提示「库存已变化，请重新确认」 | `EngineTest.planFingerprintMismatchRequiresReconfirm` |
| 可选/装饰不足导致异常失败 | 可选/装饰不足时进入 `skipped` 清单并在确认页提示「本次不加」，不挤占必需材料 | `EngineTest.optionalSkippedWithNotice` |
| 重复提交/重复撤销 | UI 层 `submitting/undoing` 禁用；数据层以草稿预分配 sessionId，同一 ID 已存在即返回 `AlreadyCommitted`；撤销检查 `session.undone` | `EngineTest.doubleCommitIdempotent` / `doubleUndoRestoresOnce`、`RoomDbTest.doubleCommitRealDbDeductsOnce` / `doubleUndoRealDbRestoresOnce` |

## 三、备份、恢复与数据库升级

| 问题 | 修复 | 验证 |
| --- | --- | --- |
| 旧备份 UUID 覆盖式合并，残留矛盾流水 | 改为「完整恢复到备份状态」：单事务清空全表后写入备份 | `RoomDbTest.fullRestoreRemovesNewerTxns`（较新流水被移除） |
| 无恢复前摘要与确认 | 设置页恢复分两步：先解析校验并展示备份时间/数据条数摘要 + 「将完整替换当前数据」警告，用户确认后才执行 | 手测路径见 GAPS.md（UI 未自动化） |
| 无恢复前快照 | 恢复前先把当前数据完整导出为内部快照；快照失败立即中止；设置页提供「回滚到恢复前快照」 | 快照写失败即抛错中止（`writeSnapshot`） |
| 校验薄弱 | `BackupCodec` 校验：应用标识、schemaVersion（拒绝更新版本）、SHA-256、空/重复 ID、流水-酒瓶/笔记-记录关联、单位合法性、数量范围、评分范围、私人配方结构可解析、ZIP 条目数/总大小/路径穿越 | `BackupCodecTest` 12 用例 |
| 仅 JSON、无版本化 ZIP | 备份改为 `manifest.json + backup.json + photos/` 的 ZIP（带版本与校验）；兼容 v1 旧 JSON 导入 | `BackupCodecTest.zipRoundTrip` / `legacyV1JsonAccepted` |
| `RecordsViewModel.backupMsg` 不进入界面状态 | 备份/恢复整体迁移到独立的设置页，消息真实进入 `SettingsState.msg` 并弹窗展示；发现页/记录页齿轮均指向设置页 | 编译 + 状态流代码审查 |
| `fallbackToDestructiveMigration` 破坏性兜底 | 移除；提供显式 `MIGRATION_1_2`（新增草稿/自定义材料表 + 调制记录配方快照列），schema 导出 2.json | `RoomDbTest.migration12PreservesData`（v1 文件库迁移后数据完整） |
| 大文件/无效压缩包 | 读取 80MB 上限、解压 64MB/2000 条目上限、路径穿越拒绝 | `BackupCodecTest.zipSlipRejected` 等 |

## 四、录入与管理

| 问题 | 修复 | 验证 |
| --- | --- | --- |
| 「搜索材料库」与「手动新增」是同一流程 | 入口页拆分为：拍照识别 / 从相册识别 / 搜索材料库 / 手动新增材料（自定义材料表单：中英文名、分类、单位维度、默认单位，持久化到 `custom_ingredients`） | `RoomDbTest.customIngredientRecipeNoteRoundTrip` |
| 剩余量只能滑块、不允许 0 和低值 | 初始容量与剩余量均支持直接输入（校验 0 ≤ 剩余 ≤ 容量），滑块仅为辅助 | 表单校验逻辑 + 编译 |
| 补充库存默认合并到第一瓶 | 重复酒瓶时必须明确点选目标瓶（单选列表），否则报错「请选择要补充到哪一瓶」 | UI 逻辑 |
| 酒瓶不能编辑/盘点/归档 | 新增酒瓶详情页：编辑品牌/酒款/容量/酒精度/开瓶/低库存阈值；盘点校正与补充生成流水；归档为软删除，历史流水保留 | 编译；流水生成走 `adjustStock`（已测精度路径） |
| 完成后「记录评分与品鉴笔记」跳到记录页 | 新增品鉴笔记编辑页：评分（0.5 步进）、甜/酸/苦/酒体、文字；完成页与记录页条目都可进入；保存/再编辑真实写库 | `RoomDbTest.customIngredientRecipeNoteRoundTrip`（笔记保存-备份-恢复一致） |
| 配方删除导致历史失真 | 提交时把配方名/英文名/杯型/酒液色快照写入调制记录，记录页优先用快照展示 | 迁移测试 + 记录页代码 |
| 无私人配方管理 | 新增编辑器：新增/从系统配方复制/编辑/删除；材料（含自定义）、用量、单位、角色、步骤、方法、杯型、风味；保存前定位具体字段错误 | `RecipeEditViewModel.validate`（字段级错误），`RoomDbTest` 私人配方往返 |
| 设置图标跳记录页 | 新增真实设置页（备份/恢复/快照回滚/数据统计/饮酒提示），发现页与记录页齿轮均指向它 | 编译 + 导航代码 |

## 五、匹配、单位与推荐

| 问题 | 修复 | 验证 |
| --- | --- | --- |
| 「合计可调杯数」跨配方重复计库存 | 首页改为：在柜材料数 / 可直接调制款数 / 替代后可调款数 | UI 代码 |
| `takeLast(3)` 取到最旧记录 | 过滤已完成且未撤销、按时间倒序 `take(3)` | 代码审查（DiscoverViewModel.buildState） |
| 替代不查方法限制、只取第一条 | `subRulesFor` 按方向 + 适用方法过滤并按优先级返回全部；匹配收集所有可用方案 | `EngineTest` 替代用例 |
| 单位缺 cl/oz/茶匙 | 补齐换算（1 oz=29.5735 ml、1 cl=10 ml、1 dash=0.5 ml、1 茶匙=5 ml），鲜果出汁保留为材料特定估算 | `EngineTest.volumeUnitConversions` / `crossDimensionNotConverted` |
| 每配方反复查库 | 新增 `SnapshotStore` 内存快照，发现/酒柜/酒谱/记录页每次重建状态用一致快照一次算完；库存 Flow 变化即自动刷新 | 编译 + 用例全绿 |

## 六、识别与配方数据

| 问题 | 修复 | 验证 |
| --- | --- | --- |
| 置信度表述为概率 | 改称「匹配分数」，并注明「规则比对得分，不是概率」；阈值对齐规格 0.85/0.60，最多 3 个候选，低匹配回退搜索/手动 | 代码 + 常量 |
| 选定后仍连续识别 | 得到候选即 `scanDone` 停止分析；离开页面 `unbindAll` + `recognizer.close()`；补齐相册识别 | 代码审查（未真机验证，见 GAPS.md） |
| 配方数量不符（README 称 20，实际 19） | 实测：原 19 款/28 材料/20 品牌/5 替代；新增 71 款（72 IBA + 16 经典 + 2 家庭简化）→ **90 款 / 81 材料 / 13 替代**，README 按实测更新 | `EngineTest.datasetIntegrity`（唯一 ID、必需材料、步骤、材料引用） |

## 七、界面体验

- 删除「§14」「本地确定性排序」「事务回滚」等开发者文案，全部换成用户可理解的说明。
- 酒柜行突出当前剩余量（大号数字），容量/开瓶天数/酒精度降为次级一行。
- 发现页新增空酒柜引导（添加第一瓶 / 先逛逛酒谱）；低库存与补货排行折叠为「补货建议」。
- 配方行的长中文名与英文名改为上下两行，避免横向挤压。
- 完成页突出实际扣减与扣后剩余量，提供品鉴与撤销入口。
- 归档/删除私人配方/恢复备份均有与其影响相符的确认对话框。

## 八、2026-09-09 变更

| 问题 | 修复 | 验证 |
| --- | --- | --- |
| 顶部内容与手机状态栏重合（edge-to-edge 下 8 个页面的自定义 topBar 未处理 insets；M3 Scaffold 仅在无 topBar 时才把顶部 inset 加给内容区） | 8 个 Screen 的自定义 topBar 加 `statusBarsPadding()`；调酒页异常分支裸 Column 同步避让 | API 37 模拟器实测：发现/记录/设置/添加酒瓶页首行内容从 y≈55px 下移至 118–127px，截图确认 |
| 拍照识别/从相册识别功能下线（用户决策） | 移除 `AddBottleScreen` 的扫描流程（scan 模式、OCR 候选匹配、CameraScanner、CandidateRow）、相机权限申请；移除 CameraX（4 项）与 ML Kit 中文识别依赖、CAMERA 权限及 manifest 中 ML Kit 传递权限的移除标记；录入入口收敛为「搜索材料库 / 手动新增材料」；流水 reason 固定为「手动新增」（历史数据中的「拍照新增」不受影响） | `assembleDebug` 构建通过；模拟器验证添加入口页仅剩两项；单元测试全绿 |
| 酒瓶详情页文案生硬；剩余量编辑允许小数；开瓶日期只能选「今天」 | 全页文案人性化（「还剩多少？」「又买了一些？」「这瓶的档案」「快喝完提醒」等）；剩余量输入只收整数（输入过滤 + 数字键盘 + 滑块取整）；编辑资料时新增开瓶日期选择（「还没开 / 已开瓶」+ 日期选择器，可改任意日期） | API 37 模拟器实测：日期选择器选 9月5日 后按钮即时更新；输入 "12.5" 中小数点被过滤 |
| 全 App 文案偏技术腔（「匹配状态」「库存流水」「反向流水」「确认入库」等） | 全部 11 个页面 + 状态徽章组件的中文案统一人性化改写（约 400 处）：行话换大白话（匹配状态→能不能调、库存流水→进出记录、归档→移出酒柜），错误提示说清怎么办，空状态给出下一步；主标题/导航/配方名/材料名/单位/合规提示保持原样；落库的 reason 字符串（「手动新增」「调制扣减」等）为兼容历史数据未动 | `assembleDebug` 构建通过；51 个单元测试全绿；模拟器抽验发现/记录页 |
