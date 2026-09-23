# 未完成与未验证事项（如实清单）

## 功能缺口

1. ~~配方数量为 90 款，未达规格 150 款的验收线。~~ ~~已于 2026-09-12 达成 150 款~~
   **已于 2026-09-12 再次调整**：经用户决策只保留 IBA 官方配方，现行为 102 款，与 IBA
   官方现行榜单（2024.11 版）一一对应。原 150 款中的 45 款经典 + 2 款家庭简化 + 9 款
   误标/已除名款（如 Barracuda、Yellow Bird 为 2024 年除名款，Gin Tonic、Gimlet、
   White Russian、Negroni Sbagliato 等从未在榜）已全部移除；曾被疑为杜撰的 IBA Tiki
   经核实为 2024 年 New Era 新增官方款，予以保留；Boulevardier、Trinidad Sour、
   Whiskey Sour、Paloma、Bramble、Lemon Drop、Jungle Bird、Tommy's Margarita、
   South Side 经核对属 IBA 官方款，已归位为 iba 来源。
2. ~~品牌库仍为 20 条~~ **已于 2026-09-12 扩充至 184 条**，覆盖 95 种材料（鲜货类 10 种不加品牌）。
   （拍照/相册识别功能已于 2026-09-09 下线，CameraX/ML Kit 依赖与相机权限一并移除。）
3. 品鉴笔记照片、酒瓶封面的 UI 入口未做（模型与备份管线已支持照片随包/恢复重建路径）。
   注意：照片链路此前在**导出侧漏传 `photos` 字段**（导出必丢照片、导入会把 `photoUri` 清成 null），
   已于 2026-09-18 修复并有 `BackupCodecTest.photosAreCarriedInBackup` 覆盖；由于功能入口本身未做，
   该链路目前仍不可由用户触达。

## 环境受限、未验证的项目

1. **界面交互的设备级验证有限**：已在模拟器验证状态栏避让；其余 Compose 交互
   （弹层、导航、Snackbar）未做系统设备级验证。`androidTest` 界面测试未编写
   （相应依赖与 testInstrumentationRunner 配置已随清理移除，需要时再补）。
   2026-09-18 的界面修复（绘制自适应缩放、保存提示、空态区分、脏检查等）同样只有编译与静态核验，
   未经设备级走查。
2. **BackupService 的 Android 文件层**（SAF Uri 读写、照片落盘与路径重建）
   未纳入 JVM 测试；已验证的是其核心 `BackupCodec`（编解码/校验/限流）与
   `BackupRestore`（真实 Room 全量替换）。2026-09-18 修复的「归档瓶随备份导出」
   在 `RoomDbTest` 中用真实库覆盖（模拟 `currentBundle()` 的取数口径），
   但 `currentBundle()` 自身的 Android 层读取仍未纳入自动化。
3. **飞行模式**：应用不声明 Internet 权限，从机制上保证离线可用；
   未在真机飞行模式下实测。
4. **构建环境**：本机 `lintVitalAnalyzeRelease` 因 AGP 8.5.2 的 UAST 与 JDK 25 不兼容而崩溃
   （环境问题，非代码问题）。release APK 可 `-x lintVitalAnalyzeRelease` 正常产出；
   lint 静态检查本轮因此未获得结果。

## 测试覆盖说明

- 64 个 JVM 用例全部通过（`./gradlew :app:testDebugUnitTest`）。
- 关键事务测试使用真实 Room + SQLite（sqlite-jdbc 驱动的自定义 OpenHelper），
  不是模拟仓库：覆盖事务回滚、幂等提交/撤销、迁移 1→4 保留数据与索引、全量恢复、草稿持久化。
- 「核心流程飞行模式可用」由「无网络权限 + 全本地数据」从架构上保证，未做设备端实测。
