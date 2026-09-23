# 一次性数据迁移脚本（已归档）

这三个脚本用于 2026-09-12 的种子数据扩充（new-ingredients/new-recipes 为新增数据，merge-seed 负责合并并生成默认步骤）。
数据已并入 `app/src/main/assets/seed/`，脚本**不可重跑**（merge-seed 对重复 ID 会直接抛错）。
保留仅供追溯；新增配方请直接编辑 assets/seed 下的 JSON，并用 `EngineTest.datasetIntegrity` 校验。
