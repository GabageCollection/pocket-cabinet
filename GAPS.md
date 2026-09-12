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
4. DataStore 设置项暂无实际持久偏好需求，设置页当前承载备份/恢复与数据信息。

## 环境受限、未验证的项目

1. **界面交互的设备级验证有限**：已在模拟器验证状态栏避让；其余 Compose 交互
   （弹层、导航、Snackbar）未做系统设备级验证。`androidTest` 界面测试未编写。
2. **BackupService 的 Android 文件层**（SAF Uri 读写、照片落盘与路径重建）
   未纳入 JVM 测试；已验证的是其核心 `BackupCodec`（编解码/校验）与
   `BackupRestore`（真实 Room 全量替换）。
3. **飞行模式**：应用不声明 Internet 权限，从机制上保证离线可用；
   未在真机飞行模式下实测。
4. ~~release APK 使用 debug 签名~~ **已于 2026-09-12 解决**:正式发布密钥 `app/pocket-cabinet-release.jks`
   (4096 位 RSA,30 年有效期),密码存根目录 `keystore.properties`(已 gitignore);无该文件时回退 debug 签名。

## 测试覆盖说明

- 50 个 JVM 用例全部通过（`./gradlew :app:testDebugUnitTest`）。
- 关键事务测试使用真实 Room + SQLite（sqlite-jdbc 驱动的自定义 OpenHelper），
  不是模拟仓库：覆盖事务回滚、幂等提交/撤销、迁移保留数据、全量恢复、草稿持久化。
- 「核心流程飞行模式可用」由「无网络权限 + 全本地数据」从架构上保证，未做设备端实测。
