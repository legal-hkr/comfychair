# Task Plan: ComfyChair 图片输入节点禁用功能

## Goal
在 MediaViewer 的工具栏中为原图输入槽位（slot 2/3/4）添加"禁用/启用"切换按钮，允许用户在图生图多图工作流中禁用不需要的图片输入节点。

## Current Phase
✅ 已完成（编译通过 + 下载链接可用）

## 测试验证
- APK 下载：`http://192.168.4.69:9003/comfychair-bypass-20260430.apk`
- 测试步骤：
  1. 打开一个多图输入的图生图工作流（如 slot 2/3/4 都有图片）
  2. 点击某张 source image（如 slot 2）
  3. 在 FloatingToolbar 中找到灰色/红色 Bypass 按钮（🚫 图标）
  4. 点击切换 — 灰色=未禁用（可点击），红色=已禁用
  5. 返回图生图界面，点击生成
  6. 验证：被禁用 slot 的 LoadImage 节点在上传工作流时被设置 `mode=4`，不再发送图片数据

## 背景调研结果

**已有实现（后端逻辑完整）：**
- `ImageToImageUiState.bypassedSourceSlots: Set<Int>` ✓ 已存在
- `ImageToImageViewModel.toggleBypassSourceImage(slot)` ✓ 已存在（第1177行）
- `WorkflowValues.bypassedSourceSlots` 持久化 ✓ 已存在
- 上传阶段检查 `bypassedSourceSlots` ✓ 已存在（ViewModel 第2036行）

**缺失部分（纯 UI 连接）：**
- `MediaViewerFloatingToolbar` 无 Bypass 按钮 ✗
- `MediaViewerScreen` 未传 `isSlotBypassed` 状态 ✗
- `MediaViewerActivity` 未传 `isSlotBypassed` Intent，未返回 `RESULT_BYPASS` ✗
- `ImageToImageScreen` 未传 `isSlotBypassed`，未处理 `RESULT_BYPASS` ✗
- `WorkflowManager.prepareImageEditingWorkflowById` 未对 bypassed slot 设置 `mode=4` ✗

**Slot → Placeholder 映射：**
| Slot | 状态字段 | Workflow 占位符 |
|------|---------|----------------|
| 1 | `sourceImage` | `{{image_filename}}` |
| 2 | `sourceImage2` | `{{image_filename_2}}` |
| 3 | `sourceImage3` | `{{image_filename_3}}` |
| 4 | `sourceImage4` | `{{image_filename_4}}` |

**设计约束：**
- Slot 1（主图）不允许 bypass（ViewModel 第1178行已强制）
- Bypass 按钮放在 `MediaViewerFloatingToolbar` 中，与 Replace 按钮并列

## Phases

### Phase 1: 后端逻辑完善
- [ ] 修改 `WorkflowManager.prepareImageEditingWorkflowById`，对 `bypassedSourceSlots` 中的 slot 将 LoadImage 节点设为 `mode=4`
- **Status:** in_progress

### Phase 2: Activity 层修改
- [ ] `MediaViewerActivity`：增加 `EXTRA_IS_SLOT_BYPASSED` Intent extra；增加 `RESULT_BYPASS` result extra；修改 `onClose` 传递 bypass result
- [ ] `MediaViewerActivity.createSingleImageIntent`：增加 `isSlotBypassed: Boolean` 参数
- **Status:** pending

### Phase 3: Screen 层修改
- [ ] `MediaViewerScreen`：将 `isSlotBypassed` 传入 `MediaViewerFloatingToolbar`；处理 `onBypassSlot` 回调（调用 `onClose` 传回 bypass 结果）
- [ ] `MediaViewerFloatingToolbar`：增加 Bypass toggle 按钮（使用 `VisibilityOff` / `Visibility` 图标，紧邻 Replace 按钮）
- **Status:** pending

### Phase 4: ImageToImageScreen 连接
- [ ] 启动 MediaViewer 时传入当前 slot 的 bypass 状态
- [ ] 处理 `RESULT_BYPASS` result，调用 `imageToImageViewModel.toggleBypassSourceImage(slot)`
- **Status:** pending

### Phase 5: 字符串资源
- [ ] 添加 `media_viewer_disable_node`、`media_viewer_enable_node` 到 `values/strings.xml` 和 `values-zh/strings.xml`
- **Status:** pending

### Phase 6: 构建验证
- [ ] `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug` 验证编译通过
- **Status:** pending

## Key Questions
1. 当 slot 被 bypass 后，图片文件是否还需要上传？ → 否，ViewModel 第2036行已检查 bypassedSlots，跳过上传
2. Bypass 后的 placeholder 如何处理？ → 在 WorkflowManager 中对 bypassed slot 的 LoadImage 节点设 `mode=4`，BypassNodeResolver 自动重连

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Bypass 按钮放在 Replace 旁边 | 和 Replace 同属"槽位操作"，体验一致 |
| Slot 1 不可 bypass | 主图是必须的，ViewModel 已强制 |
| 使用 VisibilityOff/Visibility 图标 | ComfyUI 语义：bypass = 节点不可见 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| 无 | - | - |
