# 修改记录（审查问题 → 修复 → 验证）

测试基线：`./gradlew :app:testDebugUnitTest` 64 个用例全部通过；
`:app:assembleDebug` / `:app:assembleRelease` 均构建成功。

## 〇之三、2026-09-23 普通爱好者易用性改造（UX 评审建议全量落地）

| 类别 | 修改 | 验证 |
| --- | --- | --- |
| 基础设施 | 新增 `core/data/prefs/UserPrefs.kt`（SharedPreferences 封装：`lastBackupAt`、`flavorFilterTouched`）；备份导出成功时写入时间戳 | 编译 |
| 新手引导（P0） | 加酒 entry 页顶部新增「常见酒一键添加」两列网格（20 种常见材料，满瓶默认容量直接落库，Snackbar 提示"又能多调 N 款"，已在柜中的置灰）；发现页空柜引导卡主按钮改为「一键添加常见酒」，1~2 瓶时显示「还差几瓶就能调更多」轻量提示卡 | 编译 |
| 购物清单（P0） | 新增第 5 个 Tab「清单」（`Routes.SHOPPING` + `ShoppingScreen`）：「补这些，解锁最多」（unlockRanking 前 5）、「只差一种材料」按缺失材料分组（可勾选，会话内）、「快见底了」低库存瓶直达瓶详情 | 编译 |
| 推荐（P1） | `RecommendationEngine.recommend` 新增 `notesByRecipe` 参数：配方平均评分线性加减分（(avg−3)×120），笔记甜/酸/苦均值 ≥3.5 形成口味倾向、命中风味标签 +40，理由文案同步 | `EngineTest` 新增 3 用例（评分加/减分、风味亲和），64 用例全绿 |
| 筛选（P1） | 酒谱页新增「我调过的」来源筛选（按未撤销 session 的 recipeId）与「评分优先」排序切换（未评分沉底），配方行显示「★4.5」；发现页风味 chip 在笔记 ≥3 条且用户未手动选过时按口味倾向预选一次 | 编译 + 单测 |
| 联动（P1） | 酒柜「能调 N 款」可点击 → 酒谱页按该材料（必需角色）预筛选，filter 区出现可移除 chip；`Routes.RECIPES` 改带参模板 `recipes?ing={ing}`，BottomDock active 判定改 `substringBefore('?')` | 编译 + grep 自查引用点 |
| 流程（P2） | 配方详情替代方案默认勾选每组第一条（库存已校验、优先级最高），用户可改选不覆盖；调酒完成页对扣减后见底的瓶显示「去盘点」直达瓶详情；酒柜「快见底了」点击直达清单页；BottomDock 内部自取 `DockViewModel.hasDraft`，发现 tab 有草稿时显示红点 | 编译 |
| 查找（P2） | 发现页顶栏新增统一搜索（展开式，联合命中配方与库存瓶，分段展示各前 8 条）；酒柜/酒谱顶栏各加设置齿轮；酒柜页超 30 天未备份显示可关闭的备份提醒条 | 编译 |
| 明确不做 | Records Tab 结构不动、不恢复扫码识别、清单勾选不持久化、不引入新依赖 | — |

## 〇之二、2026-09-23 静态核查发现的缺陷修复

| 类别 | 修改 | 验证 |
| --- | --- | --- |
| **领域正确性（P0）** | `DiscoverScreen`「只差一种材料」的模拟补货瓶对非「个」材料一律给 `ml` ⇒ 克（g）类必需材料换算返回 null、恒显示「能调 0 杯」。模拟逻辑下沉为 `RecommendationEngine.cupsIfRestocked`，与 `SimStore.simulate` 同样按材料自身单位入库，界面与补货建议共用一条路径 | `EngineTest.restockSimulationCountsMassIngredient`（porn-star-martini 只差 vanilla_sugar，模拟补货后杯数 > 0） |
| 清理 | `AppModule.provideRecommendationEngine` 全项目零注入点（引擎由 ViewModel 在快照上现建） | grep 确认无注入点后删除方法与独占 import |
| UI 正确性 | 加酒瓶确认页滑块仍经 `Units.fmt` 往返（Float 精度截断可致拖动卡住），与 09-18 记录不符。改为与瓶详情盘点一致：滑块直接写整数字符串，显示处再格式化 | 编译 + 静态核验 |
| UI 正确性 | 加酒瓶 `cap <= 0` 校验不分模式，但合并路径不消费 cap（只定滑块上限）→ 误伤合并保存。校验收进 `target == null`（新建）分支 | 编译 + 静态核验 |
| 并发可见性 | `AddBottleViewModel` 的 `catalogCache`/`searchIndex`/`recipesCache` 在 Main 写、`okCount()` 在 Default 读，补 `@Volatile`（与 DiscoverViewModel 的 `lastInput`/`unlockVersion` 同一做法） | 编译 |
| 可维护性 | 流水 reason「调制扣减」「撤销回滚」魔法字符串抽为 `InventoryService.REASON_MIX_DEDUCT`/`REASON_UNDO_ROLLBACK`（取值不变，历史数据兼容），写入端与 RecordsScreen 判定端统一引用 | 既有用例 `commitHonorsUserPickedBottle`（断言 reason 字面量不变） |
| 性能 | `RecordsRepository.favorites` 与 `favoriteTimes` 各自订阅 `favDao.observeAll()`；favorites 改由 `favoriteTimes.map { it.keys }` 派生，同表只订阅一次 | 编译 |
| 性能 | 调酒页 ticker `while(true){ delay(250) }` 在计时未运行时也空转；改为 `collectLatest(timerRunning)`，只在运行时 tick，状态翻转即取消 | 编译（计时 UI 行为不变） |
| 数据层 | `BottleDao.observeAll` 无 ORDER BY，同组瓶顺序依赖 SQLite 返回序；补 `ORDER BY createdAt` | 编译 + 既有 Room 用例 |
| 清理 | `app/build.gradle.kts` 死配置 `androidTestImplementation(composeBom)`（androidTest 源集已不存在） | 构建成功 |

## 〇之一、2026-09-18 全量缺陷修复（探索报告所列问题逐条落地）

| 类别 | 修改 | 验证 |
| --- | --- | --- |
| **备份（严重）** | 归档（软删除）过的酒瓶此前不随备份导出，但其历史流水仍被导出 → 校验「流水关联了不存在的酒瓶」拒绝，**只要归档过任何有流水的酒瓶，导出/摘要/恢复/回滚四条路全部不可用**。`BottleDao` 拆为 `getActive()`（业务用）与 `getAll()`（备份用，含归档） | `RoomDbTest.archivedBottleSurvivesBackupRoundTrip`（真实库：归档后导出→校验通过）、`BackupCodecTest.archivedBottleBackupIsSelfConsistent` |
| **备份（严重）** | `BackupService.currentBundle()` 建好 photo 映射却**没有传进 `Bundle`** → 导出静默丢照片，且 `photoUri` 已被改写成 `backup-photo:` 标记，导入/回滚时无对应文件而被清成 null。补 `photos = photos` | `BackupCodecTest.photosAreCarriedInBackup` |
| 备份（健壮性） | 解压限流与读取上限不一致（80 MiB 先整读再被 65 MiB 拒）；schema>1 但缺 kv 段的截断备份会被当成完整备份 | 读取上限对齐 65 MiB；补「备份数据不完整」校验；`BackupCodecTest.missingKvSectionRejected` |
| **领域正确性** | 用户确认替代后，UI 按**原材料 ID** 存的选瓶被静默忽略（领域层按 targetId 取 override）→ 改为随需求行携带原材料 ID 的选瓶，仅当该瓶确属目标材料时生效 | `EngineTest.substitutionHonorsUserPickedBottle`（青柠→黄柠檬替代，用户选的柠檬瓶仍被优先取用） |
| 领域正确性 | 调制记录 `startedAt` 未传，取默认 now ⇒ 恒等于 `finishedAt`，调制时长永远为 0。改为取草稿 `createdAt` | `EngineTest.sessionStartComesFromDraft` |
| 领域正确性 | 撤销时若目标瓶不存在，`restore` 静默返回 0 → 留下「流水已回滚、库存没恢复」的不一致。改为抛错触发整体回滚 | 编译 + 既有撤销用例 |
| 领域正确性 | 补货建议的模拟瓶对非「个」材料一律给 `ml` ⇒ g（质量维度）材料换算返回 null、永远进不了建议。改为按材料自身单位模拟 | `EngineTest.unlockRankingIncludesMassIngredients` |
| 种子单位 | `撮`、`片皮` 不在 `Units.ALL_UNITS` 内（此前靠「只出现在装饰行」侥幸不报错）。纳入单位表，「片皮」按 1 个 ≈ 8 片皮换算、「撮」不跨单位换算；加载期新增单位白名单校验（材料行严格、步骤用量允许「适量/少许」与「dash（可省）」写法） | `EngineTest.seedUnitsAreKnown` / `peelAndPinchUnits`；102 款配方全部通过加载校验 |
| 数据层 | 「直接用量 + 替代」聚合到同一步骤时，非换算单位只记 problems 而不落 skipped，用户看不到「本次不加」 | 可选/装饰的非换算单位进 `skipped` 并在确认页提示 |
| 数据层 | 9 张表无索引，`transactions` 是唯一持续增长的表却按 `sessionId`/`time` 全表扫 | Room v4 + `MIGRATION_3_4` 补 4 个索引；`RoomDbTest.migrationPreservesData` 断言索引存在 |
| 数据层 | `customRecipeById` 全表读入内存再过滤；自定义材料/私人配方的 `createdAt` 被 upsert(REPLACE) 反复重置为 now | 改为 SQL 按 id 查；`save*` 显式保留既有 createdAt | 编译 + 既有往返用例 |
| **UI 正确性** | 配方详情「配方被删除」与「加载中」不可区分 → 永久转圈且没有返回键。加 `loaded`/`notFound` 区分，显示占位 + 顶栏返回 | 代码审查（UI-A 核验） |
| UI 正确性 | 「进度已保存」写方写 `previousBackStackEntry`、读方读自己 entry → 从配方详情进入调酒时提示必然丢失。统一 key 与把柄，两条入口都能弹 | 代码审查（UI-A 核验） |
| UI 正确性 | 「补货建议」惰性计算作废后不重算（展开状态下库存一变整段永久消失）、`loadUnlocks` 无重试 | 引入 `unlockVersion` 作驱动源 + 过期结果丢弃 |
| UI 正确性 | 「换一杯看看」计数无界自增，溢出为负后取模越界 | 钳制到固定循环长度 |
| UI 正确性 | 酒柜「筛选无结果」误显示「酒柜还是空的」 | 区分真空态与筛选空态（后者带「看全部」动作） |
| UI 正确性 | `GlassPour`/`BottlePour` 在固定 `size` 下 `aspectRatio` 失效、又按宽度算 scale ⇒ 画出来比给定高度高（44×72 实际约 99dp），压到相邻行 | 外层 `Box` + `scale = min(宽比, 高比)` + 居中；调用点零改动 |
| UI 正确性 | `startMix` 无 try/catch（抛错直接崩溃且无 UI 出口）、`delete()` 同理 | 补异常处理并写入 state 用 `MessageDialog` 展示 |
| UI 正确性 | 编辑框 `remember` 缺 `b.id` key，切换酒瓶残留上一瓶输入（瓶详情、配方编辑行） | 补 key |
| UI 正确性 | `RecordsScreen` 的 `t.sessionId!!` 跨函数推理非空、`RecipeDetailScreen`/`MixSessionScreen` 多处冗余 `!!` | 改局部变量判空 / 用智能转型 |
| UI 正确性 | `NoteEditorScreen` 保存失败静默吞掉、编辑中途返回直接丢弃 | 补 `error` + `MessageDialog` + `BackHandler` 脏检查 |
| UI 正确性 | `AddBottleScreen` 合并库存按目标瓶单位解释 `rem` 而 UI 按材料单位标注（单位不一致写错数量）；滑块经 `Units.fmt` 往返截断导致拖动卡住 | 统一 `inputUnit` 后再显示与写入；滑块保留数值状态 |
| UI 正确性 | 顶层 `SimpleDateFormat` 单例非线程安全（3 屏） | 改组合期 `remember` |
| 重复代码 | `InfoRow`/`SettingsRow`、`EditField`/`FieldWithError` 两两重合；低库存判定散落 4 处 | 抽共享组件与 `Bottle.isLowStock()/remainingPct()` 唯一来源 |
| 清理 | 死字段（`pickerOpen`/`pickerQuery`/`baseRecipeId`/`editingId`）、零引用令牌 `AmberType.body`、未使用导入 | 删除；未使用导入扫描为 0 |
| 文档 | README 用例数与迁移版本、CHANGELOG/GAPS 与本轮实际对齐 | 见各文件 |

## 〇、2026-09-16 动画路线图全部落地

| 类别 | 修改 | 验证 |
| --- | --- | --- |
| 动效令牌 | 新增 `AmberMotion`（fast/med/slow 三档 tween + stateSpring/bounceSpring），全项目动画不再散落字面量时长 | 编译 |
| 列表动画 | 11 处 LazyColumn 全部 `Modifier.animateItem()`（前置 key 上一轮已就位） | 编译 |
| 状态切换 | 今晚推荐/杯数/步骤切换用可中断 `AnimatedContent`；补货建议/草稿卡用 `AnimatedVisibility`；5 屏 loading→内容 `Crossfade`；Tab/编辑模式 `Crossfade` | 编译 + 代码审查 |
| 库存反馈 | `StockBar` 改 `graphicsLayer{scaleX}`（免每帧 relayout）+ 颜色过渡；`BottlePour` 液位弹簧动画内置（调用点零改动）+ 液面波纹（静止归零）；`StatusBadge` 颜色过渡 | 编译 |
| 微交互 | 收藏星/评星弹性缩放；逐步调酒进度点渐变色 | 编译 |
| 导航 | 详情类推入右滑+淡入、返回反向；tab 保持淡入淡出；配方列表→详情杯型共享元素飞行（Compose 1.7.5 无内置 scope local，用应用级 `LocalAmberSharedScope`/`LocalAmberAnimScope` 传递，null 自动降级） | 编译；真机走查待补 |
| 签名动画 | 完成调制页 `GlassPour` 从 0 倒满（一次性，非循环） | 编译；真机走查待补 |

## 一、2026-09-15 全项目代码审查后的简化与优化

| 类别 | 修改 | 验证 |
| --- | --- | --- |
| 性能：领域计算全在主线程 | 所有 ViewModel 的昂贵计算移入 `flowOn(Dispatchers.Default)`；搜索词/筛选/tab 等 UI 状态从重计算管线拆出，只对预算好的结果做纯 List 操作 | 编译 + 50 测试 |
| 性能：同一份 match 重复计算 | `RecommendationEngine.recommend` 改为接收预算好的 matched；Cabinet「能调几款」从单次全量匹配推导（删 `bottleUsage`，O(瓶×配方)→O(配方)）；`unlockRanking` 只重测受影响配方且改为展开时惰性计算；RecipeDetail 删去重复的第二次 match | `EngineTest.recommendationExplainable` / `recentSuppression` / `unlockRankingTopIsSweetVermouth` |
| 性能：Compose | `collectAsStateWithLifecycle` 全覆盖；LazyColumn 全部补 `key=`；`Pour.kt` 路径解析缓存（lazy）+ 颜色 `remember`；加酒瓶/配方编辑的搜索索引预建 | 编译 + 代码审查 |
| 正确性 | 配方详情加载期白屏（Scaffold 前先出顶栏）；`Units.fmt` 改 BigDecimal（消除 locale 影响）；备份解压分块限流（ZIP 炸弹防护）；品鑑半星用容差比较；合并库存按目标瓶单位换算；计时器按 `timerStep` 归属恢复 | 编译 + `BackupCodecTest` 12 用例 |
| 数据模型 | `MixSession` 删除恒为 "done" 的 `status` 与零读取的 `currentStep`（Room v3，`MIGRATION_2_3` 表重建）；`mix_drafts` 新增 `timerStep`；`RecipeStep.needs` 由 Triple 改为 `StepNeed`；删除死模型 `Favorite` | `RoomDbTest.migrationPreservesData` / `draftSurvivesReopen` |
| 重复代码 | 配方 JSON 解析抽取为共享 `RecipeJson`（SeedCatalog/CustomRecipeCodec 共用）；`Bundle.toSummary`；映射器全部命名参数且对称；新增共享组件 `AmberTopBar`/`MessageDialog`/`EmptyHint`/`Flavor`/`SubChoice.line` | 编译 + 测试 |
| 清理 | 删除未使用依赖（datastore、turbine、room-testing、androidTest 两项）；删除 12 处未用导入与 ProGuard 残留；归档一次性种子迁移脚本到 `tools/archive/`；`gradlew` 补可执行位 | 构建成功 |

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
