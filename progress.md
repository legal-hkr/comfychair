# Progress: 图片历史选择器功能

## 2026-04-30

### Phase 1: 调研 ✅（已更新理解）

**新发现**:
- `GalleryPickerBottomSheet.kt` 已存在（3列网格布局）
- `ImageToImageViewModel` 已有 `onSourceImageFromGallery(context, slot, bitmap)` 方法
- 底部工具条 GalleryPicker 按钮（780-791行）是错误位置，**需删除**
- **正确入口**: MediaViewerFloatingToolbar 中的 "Use as source" 按钮
- `mediaViewerReplaceLauncher`（224-243行）接收到 `RESULT_SLOT` 后调用 `imagePickerLauncher` 打开系统文件选择器 → **需改为打开 GalleryPickerBottomSheet**

### Phase 2: 实现 ✅

**实际代码状态（已就绪）**:
- `mediaViewerReplaceLauncher`（236-238行）已改为 `showGalleryPicker = true`
- `currentPickerSlot` 状态已存在（177行）
- `GalleryPickerBottomSheet` onSelect 已使用 `currentPickerSlot`（807行）
- 底部工具条 GalleryPicker 按钮已删除

### Phase 3: 验证 ✅

- [x] `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug` 编译成功
- [x] APK 生成: `app/build/outputs/apk/debug/app-debug.apk` (86MB)

---

## 错误记录

（暂无）
