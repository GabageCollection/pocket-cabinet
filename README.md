# 口袋酒柜 · Pocket Cabinet（原生 Android 实现）

依据《2026-09-02 家庭调酒与定量酒柜 Android App 设计规格》实现的原生 Android 应用。
技术栈遵循规格 §10.1：Kotlin + Jetpack Compose (Material 3) + Navigation Compose +
ViewModel/Coroutines/Flow + Room + Hilt。
（拍照/相册识别功能已下线：CameraX 与 ML Kit 依赖、相机权限已移除，酒瓶录入走搜索材料库与手动新增。）

- 目标平台：Android 8.0（API 26）及以上，compileSdk/targetSdk 34
- 完全离线：AndroidManifest 不声明 Internet 权限
- 视觉：深棕黑 #100E0C + 琥珀金 #D6A45D，配方状态四色遵循 §7.1；杯型/瓶型由 Canvas 本地绘制

## 分层

    core/
      model/        领域模型（含 MixDraft 调酒草稿、MixSession 配方快照）
      units/        Qty（统一 0.001 精度策略）+ Units（同维度确定性换算）
      domain/       MatchEngine（§7.1/7.2，多替代方案）· InventoryService（聚合扣减计划、
                    幂等提交、指纹一致性、幂等撤销）· RecommendationEngine（§8 可解释推荐）
      data/
        db/         Room v2 实体 / DAO / 显式迁移（MIGRATION_1_2，无破坏性兜底）
        seed/       SeedCatalog：版本化 JSON 随包，加载即校验
        repo/       Repository（扣减在 withTransaction 内执行）· BackupCodec（ZIP+校验，
                    纯 JVM 可测）· BackupRestore（全量替换）· BackupService（Android 文件层）
    ui/
      theme/        设计令牌（深棕黑 + 琥珀金 + 状态四色）
      components/   GlassPour / BottlePour（Canvas）· StatusBadge · StockBar · BottomDock
      screens/      发现 / 酒柜 / 酒谱 / 记录 / 设置 / 配方详情 / 逐步调酒 / 添加酒瓶 /
                    酒瓶详情 / 品鉴笔记 / 私人配方编辑
    di/             Hilt 模块

## 数据集（实际数量，以 assets/seed 为准）

| 数据 | 数量 |
| --- | --- |
| 系统配方 | **102 款**（全部为 IBA 官方现行配方，即 2024.11 版榜单三大组各 34 款；数据版本 2026.09.12-2） |
| 材料 | 105 种 |
| 替代规则 | 13 条（单层、有方向、含比例/风味影响/适用方法/优先级） |
| 品牌记录 | 184 条（覆盖 95 种材料） |

✅ 配方集已对齐 IBA 官方现行榜单（2024.11 版，102 款：The Unforgettables / Contemporary
Classics / New Era Drinks 各 34 款），与官方榜单一一对应。原规格 150 款目标经用户决策
调整为"只保留 IBA 官方配方"；非官方的经典/现代经典/家庭简化款已全部移除
（Boulevardier、Trinidad Sour、Whiskey Sour 等实为 IBA 官方款，已归位并修正来源标注，
Trinidad Sour 材料恢复为官方配方杏仁糖浆）。扩充时向 `assets/seed/recipes.json`
追加并通过 `EngineTest.datasetIntegrity` 校验。

## 单位与精度

- 库存、扣减、流水、备份统一 0.001 单位精度（`Qty.round`），0.125 个等分数用量精确保留。
- 换算标准：1 oz = 29.5735 ml；1 cl = 10 ml；1 dash = 0.5 ml；1 茶匙 = 5 ml；
  1 个青柠 ≈ 4 角、1 个柠檬 ≈ 8 片皮（估算）。鲜果出汁（yieldMl）为材料特定估算换算。
- 不同维度（ml / g / 个）不自动互转；界面显示值不回写库存。

## 测试

`app/src/test/` 共 **51 个 JVM 用例，全部通过**：

- `EngineTest`（30）：状态四态与最大杯数、必需/可选/装饰/常备、单层替代（方向/方法过滤）、
  多瓶选择与跨瓶扣减、用户指定瓶优先扣减、未确认替代不执行、重复材料聚合占用、
  可选材料不足跳过、0.125 分数用量多次扣减/撤销一致性、同一草稿重复提交幂等、
  重复撤销只恢复一次、计划指纹变化拒绝提交、补一瓶酒解锁、推荐可解释性、数据集完整性、单位维度。
- `RoomDbTest`（8，**真实 Room + sqlite-jdbc**，非模拟）：扣减中途异常真实事务回滚、
  真实库重复提交只扣一次、重复撤销只恢复一次、草稿跨进程重建恢复、迁移 1→2 保留数据、
  旧备份全量恢复不残留矛盾流水、损坏备份不改变数据、自定义材料+私人配方+品鉴记录往返。
- `BackupCodecTest`（12）：ZIP 往返、篡改/错误应用/新版本/悬空关联/非法单位/超范围数量/
  重复 ID/ZIP 路径穿越拒绝、v1 旧格式兼容、摘要解析。
- `TxSanityTest`：Room withTransaction 真实回滚健全性。
- `testdb/JdbcSQLiteOpenHelper`：以 sqlite-jdbc 驱动的真实 SQLite OpenHelper
  （含 Android 语义的嵌套事务栈），让 Room 测试无需模拟器。

## 构建

    ./gradlew :app:testDebugUnitTest    # 51 个单元/数据库测试
    ./gradlew :app:assembleDebug        # 可安装测试 APK（debug 签名）
    ./gradlew :app:assembleRelease      # 压缩优化的测试 APK（无发布签名，用 debug 签名）

交付物（工作区根目录 `Drink list/`）：
- `pocket-cabinet-1.0.0-debug.apk` — debug 构建，可直接安装测试
- `pocket-cabinet-1.0.0-release-debugsigned.apk` — release 构建（minify），**debug 签名**，
  仅为测试安装用；没有真实发布签名，不是正式发布包。

## 主要修复与文档

- 修改记录：见 `CHANGELOG.md`
- 未完成/未验证事项（含环境限制）：见 `GAPS.md`
