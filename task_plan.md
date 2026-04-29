# Task Plan: ComfyChair 多图输入支持

## Goal
为 ComfyChair 图生图（Image-to-Image）功能增加多图选择支持，允许用户选择多张图片作为 workflow 的多个 LoadImage 节点输入。

## Current Phase
Phase 1

## Phases

### Phase 1: 调研与架构分析 ✅ complete
- [x] 调研 TemplateKeyRegistry 映射机制
- [x] 调研 FieldMappingAnalyzer 候选节点检测逻辑
- [x] 调研 WorkflowEditorScreen 字段映射 UI
- [x] 调研 ImageToImageScreen 图片选择 UI
- [x] 调研 reference_image_1/2 的占位符驱动机制
- [x] 定位根因：TemplateKeyRegistry 只有单一 `image` 字段映射
- **Status:** complete

### Phase 2: 核心数据层改造
- [ ] `TemplateKeyRegistry.kt` — 添加 `image_filename_2`、`image_filename_3`、`image_filename_4` 映射
- [ ] `FieldDisplayRegistry.kt` — 添加新字段的显示名称资源映射
- [ ] String resources — 添加 `image2`、`image3`、`image4` 的标签字符串
- **Status:** pending

### Phase 3: 字段映射 UI 改造
- [ ] `WorkflowEditorScreen.kt` — 确保多个 image 字段都能显示为独立的映射行
- [ ] `FieldMappingState.kt` — 支持多个 image 字段独立选择节点
- **Status:** pending

### Phase 4: 图生图预览 UI 改造
- [ ] `ImageToImageScreen.kt` — 预览区从单图改为 LazyRow 多图展示
- [ ] `ImageToImageViewModel.kt` — `sourceImage: Bitmap?` 改为 `sourceImages: List<Bitmap>`
- [ ] 图片选择器支持添加/删除多张图片
- **Status:** pending

### Phase 5: Workflow 构建与上传逻辑
- [ ] `WorkflowManager.kt` — 循环上传多张图片，构建多个 LoadImage 节点输入
- [ ] `ComfyUIClient.kt` — workflow JSON 构建支持动态多图节点
- **Status:** pending

### Phase 6: 测试验证
- [ ] 编译验证
- [ ] 手动测试多图选择流程
- **Status:** pending

## Key Questions
1. 最多支持几张图片？（建议4张，与 CLIP 字段数量一致）
2. workflow JSON 中第二个 LoadImage 节点的占位符名称：`{{image_filename_2}}` 还是 `{{image_2}}`？
3. 多图选择时是否需要保持顺序（影响构图）？

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 添加 `image_filename_2/3/4` 字段 | 与现有 `clip_name1/2/3/4` 命名风格一致 |
| 最大支持4张图片 | 与 CLIP 多槽位设计对齐，避免过度复杂 |
| 使用 `{{image_filename_N}}` 占位符 | 与现有 `image_filename` 映射一致，复用 KEY_TO_PLACEHOLDER 逻辑 |
| 多图用 LazyRow 展示 | 与 ComfyUI 习惯一致，支持横向滚动 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| 无 | - | - |

## Notes
- Nick 偏好：总分结构，先案例后框架，逐层拆解
- 根因已确认：TemplateKeyRegistry 只定义单一 `image` 字段，多个 LoadImage 节点会互相覆盖
- 参考图机制是占位符驱动（`{{reference_image_1}}`、`{{reference_image_2}}`），与 source image 的模板系统是两条独立路径
