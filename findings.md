# Findings & Decisions

## Requirements
- 图生图（Image-to-Image）功能支持多图选择
- 用户 workflow 有两个 LoadImage 节点，目前只能映射一个
- 参考现有 reference_image_1/2 的实现方式
- 不修改 ComfyUI 服务器端

## Research Findings

### 1. 根因：TemplateKeyRegistry 只有单一 image 映射
`TemplateKeyRegistry.kt` 第 40 行：
```kotlin
"image_filename" to "image"
```
`KEY_TO_PLACEHOLDER` 第 78 行：
```kotlin
"image" to "image_filename"
```
**问题**：当 workflow 有两个 LoadImage 时，FieldMappingAnalyzer 找到了两个 candidates，但系统只有一张"image"字段可以映射，自动选中第一个，第二个无处可去。

### 2. FieldMappingAnalyzer 候选节点检测逻辑
`FieldMappingAnalyzer.kt:133-157`：
- 策略1：直接匹配 — 遍历所有节点，找 input key 匹配 `image` 的节点，且值匹配 `{{image_filename}}`
- 策略2（fallback）：对于 `image` 字段，找有 IMAGE output + ENUM inputs 的节点
- 两个 LoadImage 都会被找到为 candidates，但 `selectedCandidateIndex` 只支持选一个

### 3. reference_image_1/2 是占位符驱动的特例
`WorkflowCapabilities.kt:113-114`：
```kotlin
"reference_image_1" in placeholders  →  hasReferenceImage1 = true
"reference_image_2" in placeholders  →  hasReferenceImage2 = true
```
占位符检测用正则 `\{\{(\w+)\}\}` 扫描整个 workflow JSON，与节点类型无关。这是硬编码的特例，不是通用字段映射系统。

### 4. 字段映射保存机制
`WorkflowManager.kt:638-669`：
- `fieldMappings: Map<String, Pair<String, String>>` — fieldKey → (nodeId, inputKey)
- 保存时 `applyFieldMappings()` 将真实值替换为 `{{placeholderName}}`
- 如果 workflow 有两个 LoadImage，都映射到同一个 fieldKey `"image"`，保存时会互相覆盖

### 5. WorkflowEditorScreen 映射行渲染
`WorkflowEditorScreen.kt:1287-1330`：
- `requiredFields` 和 `optionalFields` 来自 `mappingState.fieldMappings`
- 每个 `FieldMappingState` 渲染为一行的 `FieldMappingRow`
- 支持 `hasMultipleCandidates`（多个候选节点），但每次只选一个

### 6. ImageToImageScreen 图片选择
`ImageToImageScreen.kt`：
- 使用 `OpenDocument()` 单选图片
- `ImageToImageViewModel` 状态：`sourceImage: Bitmap?`（单张）
- `referenceImage1/2` 是可选的参考图，独立于 source image

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 添加 `image_filename_2/3/4` 字段 | 与 clip_name1/2/3/4 命名风格一致 |
| 最大4张图片 | 与 CLIP 多槽位对齐，避免过度复杂 |
| 使用 `{{image_filename_N}}` 占位符 | 复用现有 `image_filename` 映射逻辑 |
| 多图预览用 LazyRow | 横向滚动，与 ComfyUI 习惯一致 |

## Key Files
| 文件 | 作用 | 关键行 |
|------|------|--------|
| `TemplateKeyRegistry.kt` | 字段映射注册表 | 40, 78 |
| `FieldMappingAnalyzer.kt` | 候选节点检测 | 133-157, 159-185 |
| `FieldMapping.kt` | 映射状态数据类 | 34-91 |
| `WorkflowEditorScreen.kt` | 映射 UI | 1287-1330 |
| `WorkflowCapabilities.kt` | 占位符检测 | 113-114 |
| `ImageToImageScreen.kt` | 图生图 UI | OpenDocument() 单选 |
| `ImageToImageViewModel.kt` | 图生图状态 | sourceImage: Bitmap? |
| `WorkflowManager.kt` | 保存时替换占位符 | 638-669 |
