# Findings & Decisions

## Requirements
- 为 ComfyChair 图生图（Editing 模式）添加多图片输入支持
- 修复 Server Error 400（`{{upscale_method}}` 和 `{{ckpt_name}}` 未替换）

## Research Findings

### 1. 图片上传链路（Editing 模式）
`ImageToImageViewModel.prepareEditingWorkflow()` (line 2034-2068):
- `sourceImage` → 上传为 `editing_source_*.png`
- `sourceImage2/3/4` → 上传为 `editing_source_2/3/4_*.png`
- `referenceImage1/2` → 上传为 `reference_1/2_*.png`

上传后调用：
```kotlin
WorkflowManager.prepareImageEditingWorkflowById(
    workflowId = state.selectedEditingWorkflowId,
    ...
    sourceImageFilename = uploadedSource,
    sourceImage2Filename = uploadedSource2,  // nullable
    sourceImage3Filename = uploadedSource3,  // nullable
    sourceImage4Filename = uploadedSource4,  // nullable
    referenceImage1Filename = uploadedRef1,  // nullable
    referenceImage2Filename = uploadedRef2   // nullable
)
```

### 2. 占位符替换逻辑（Editing）
`WorkflowManager.prepareImageEditingWorkflowById()` (line 1803-1951):

替换代码覆盖：
- `{{positive_prompt}}`, `{{negative_prompt}}`
- `{{ckpt_name}}` — **有条件**：`if (checkpoint.isNotEmpty())`
- `{{unet_name}}`, `{{lora_name}}`, `{{vae_name}}`, `{{clip_name}}`
- `{{clip_name1/2/3/4}}` — nullable let 语法
- `{{text_encoder_name}}`, `{{latent_upscale_model}}`
- `{{highnoise_unet_name}}`, `{{lownoise_unet_name}}`
- `{{highnoise_lora_name}}`, `{{lownoise_lora_name}}`
- `{{image_filename}}`, `{{image_filename_2}}`, `{{image_filename_3}}`, `{{image_filename_4}}`
- `{{reference_image_1}}`, `{{reference_image_2}}`
- 通用参数通过 `replaceCommonPlaceholders()` 处理（seed, steps, cfg, sampler, scheduler, denoise, megapixels）

**缺失的占位符替换：**
- `{{upscale_method}}` — **完全没有替换逻辑**

### 3. Server Error 400 根因
错误信息：
```
upscale_method: '{{upscale_method}}' not in ['lanczos', 'bicubic', 'area']
ckpt_name: '{{ckpt_name}}' not in ['Qwen-Rapid-AIO-NSFW-v23.safetensors', 'qwenImageEditRemix_aioV20.safetensors']
```

说明：
- Editing workflow 中存在 `{{upscale_method}}` 占位符，但代码从未替换
- `{{ckpt_name}}` 出现在 CheckpointLoaderSimple 节点，但 Editing 模式下 `selectedEditingCheckpoint` 可能为空或未正确传递

### 4. prepareEditingWorkflow 的 ckpt_name 条件替换
```kotlin
// line 1809-1811
if (checkpoint.isNotEmpty()) {
    processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
}
```
Editing 模式传入的 `checkpoint` 参数来自 `state.selectedEditingCheckpoint`。如果为空字符串，`{{ckpt_name}}` 不会被替换。

### 5. 额外发现的 Editing 模式 Bug
```kotlin
// line 2149-2150
val baseWorkflow = WorkflowManager.prepareImageToImageWorkflowById(
    ...
    latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
```
在 Inpainting 模式（`prepareInpaintingWorkflow`）中调用 `prepareImageToImageWorkflowById` 时，`upscale_method` 和 `latent_upscale_model` 都会被传递，但这些字段在 Editing workflow 中可能使用不同的占位符。

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 提交前预检验 + 用户确认对话框 | 在 ComfyUI 返回 400 之前拦截；用户能明确知道哪个参数未设置 |
| 不在 `WorkflowManager` 中为每个占位符添加默认值 | 会掩盖配置不完整的问题，用户无法知情 |
| 预检验逻辑放在 `prepareWorkflow()` 返回前 | 统一入口，所有模式（Editing/Inpainting）都能受益 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Server Error 400: `{{upscale_method}}` | 需要在 `prepareImageEditingWorkflowById()` 添加 `upscale_method` 替换 |
| Server Error 400: `{{ckpt_name}}` | Editing 模式下 `selectedEditingCheckpoint` 传递检查，确认 workflow 类型 |

## Resources
- `/root/comfychair/app/src/main/java/sh/hnet/comfychair/WorkflowManager.kt` — 核心 workflow 替换逻辑
- `/root/comfychair/app/src/main/java/sh/hnet/comfychair/viewmodel/ImageToImageViewModel.kt` — UI 状态和 prepareWorkflow
- `/root/comfychair/app/src/main/java/sh/hnet/comfychair/ComfyUIClient.kt` — 图片上传到 ComfyUI

## Visual/Browser Findings
- 错误来自 ComfyUI 服务器端验证：workflow JSON 中的 `{{upscale_method}}` 被作为字面字符串发送到 `TextEncodeQwenImageEditPlusAdvance_lrzjason` 节点
- ComfyUI 返回的 allowed values: `['lanczos', 'bicubic', 'area']`，说明这是一个 dropdown 类型的 input
