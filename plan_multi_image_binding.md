# 多图绑定 — 自定义节点映射支持

## 背景

用户报告：在 Workflow Editor 中，`image_filename_2/3/4` 找不到节点，无法在 App 内自定义绑定。

## 根因分析

### 现有机制

1. `WorkflowEditorScreen` 的 `FieldMappingPanel` 从 `mappingState.fieldMappings` 渲染字段列表
2. `mappingState` 由 `FieldMappingAnalyzer.analyzeWorkflowMappings()` 构建
3. 可选字段列表 = `TemplateKeyRegistry.getOptionalKeysForType(type)` = `UNIVERSAL_OPTIONAL_KEYS`
4. `UNIVERSAL_OPTIONAL_KEYS` 已包含 `image_filename_2/3/4` ✅
5. `doesValueMatchPlaceholder` 对 `fieldKey.startsWith("image_filename_")` 有特殊处理，精确匹配 `{{image_filename_2}}` ✅

### 问题所在

`findCandidatesForField("image_filename_2", ...)` 只会找**值已经是 `{{image_filename_2}}`** 的 LoadImage 节点。如果 workflow JSON 中某个 LoadImage 节点的 image 输入值是 `{{image_filename}}`（或其他普通文件名），它不会被识别为候选。

但更深层的问题是：**如果 workflow JSON 中没有任何 `{{image_filename_2}}` 占位符，`image_filename_2` 这个字段虽然技术上在 `UNIVERSAL_OPTIONAL_KEYS` 里，但用户可能看不到它出现在映射行列表中**（取决于 JSON 里是否真的有这个占位符值）。

最终根因：`KEY_TO_PLACEHOLDER` 里没有 `image_filename_2/3/4` 的映射。当一个 LoadImage 节点被绑定到 `image_filename_2` 时，系统无法正确将其值从普通文件名替换为 `{{image_filename_2}}`。

## 解决方案

在 `TemplateKeyRegistry.kt` 中：

1. **`KEY_TO_PLACEHOLDER`** 加入 `image_filename_2 → image_filename_2`、`image_filename_3 → image_filename_3`、`image_filename_4 → image_filename_4`（让这些序号字段能正确从 fieldKey 反查到自己的 placeholder 名）
2. **验证** `UNIVERSAL_OPTIONAL_KEYS` 包含 `image_filename_2/3/4`（已确认包含 ✅）
3. **`PLACEHOLDER_TO_KEY`** 中 `image_filename_2/3/4 → "image"` 映射已存在 ✅

## 代码改动

### `TemplateKeyRegistry.kt`

在 `KEY_TO_PLACEHOLDER` map 中添加：

```kotlin
"image_filename_2" to "image_filename_2",
"image_filename_3" to "image_filename_3",
"image_filename_4" to "image_filename_4",
```

位置：在 line 82 (`"image" to "image_filename"` 之后)

## 验证方法

1. 编译：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug`
2. 部署 APK
3. 打开 Workflow Editor（上传或选择一个有两个 LoadImage 节点的 workflow）
4. 在 Optional Fields 区域，确认能看到 `Image 2`、`Image 3`、`Image 4` 映射行
5. 将不同的 LoadImage 节点分别绑定到 `Image`、`Image 2`、`Image 3` 字段
6. 保存并执行 workflow，验证各节点正确加载不同图片

## 影响范围

- 仅修改 `TemplateKeyRegistry.kt`（一个 map 添加 3 个条目）
- 不影响现有 `image`（主图）字段的行为
- 不影响 `image_filename_2/3/4` 的候选匹配逻辑（已有 `startsWith` 分支处理）
