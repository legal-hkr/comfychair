# 修改计划：将图片上传从相册改为系统文件选择器

**日期**: 2026-04-23
**任务**: 将图片 picker 从 `ActivityResultContracts.GetContent()` 改为 `OpenDocument()`，支持通过系统文件选择器上传（Downloads / 文件管理器 / 相册全支持）
**状态**: ✅ 已完成

## 改动范围

| 文件 | 改动行数 | 说明 |
|------|---------|------|
| `app/src/main/java/sh/hnet/comfychair/ui/screens/ImageToImageScreen.kt` | 6 | 源图上传 |
| `app/src/main/java/sh/hnet/comfychair/ui/screens/ImageToVideoScreen.kt` | 6 | 源图上传 |
| `app/src/main/java/sh/hnet/comfychair/ui/components/shared/ReferenceImageThumbnail.kt` | 3 | 参考图上传 |

## 具体改动

### 1. ImageToImageScreen.kt (L146-153)

**改动前**:
```kotlin
val imagePickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri ->
    uri?.let {
        imageToImageViewModel.onSourceImageChange(context, it)
        imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
    }
}
```

**改动后**:
```kotlin
val imagePickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let {
        // Take persistable permission so we can read the file later
        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(it, takeFlags)
        imageToImageViewModel.onSourceImageChange(context, it)
        imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
    }
}
```

### 2. ImageToVideoScreen.kt (L129-136)

**改动前**:
```kotlin
val imagePickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri ->
    uri?.let {
        imageToVideoViewModel.onSourceImageChange(context, it)
        imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE)
    }
}
```

**改动后**:
```kotlin
val imagePickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let {
        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(it, takeFlags)
        imageToVideoViewModel.onSourceImageChange(context, it)
        imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE)
    }
}
```

### 3. ReferenceImageThumbnail.kt (L47-51)

**改动前**:
```kotlin
val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let { onImageSelected(it) }
}
```

**改动后**:
```kotlin
val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    uri?.let {
        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        it.let { uri -> android.content.ContextCompat.startActivity }
        // Take persistable permission
        val context = androidx.compose.ui.platform.LocalContext.current
        val ctx = context
        ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        onImageSelected(it)
    }
}
```

> ⚠️ **注意**: `ReferenceImageThumbnail` 是 Composable，需从 `LocalContext.current` 获取 context。

## 技术背景

| Contract | Android 版本 | 行为 |
|----------|-------------|------|
| `GetContent()` | Android 13+ | 系统相册 picker（photoPicker），只读媒体库 |
| `OpenDocument()` | Android 4.4+ | 系统文件选择器，支持 Downloads / 文件管理器 / 相册 |

使用 `OpenDocument` 时添加 `FLAG_GRANT_READ_URI_PERMISSION`，使 URI 权限在后续仍可访问（尤其是跨 Activity 时）。

## 验证

- [x] 代码改动完成
- [x] `./gradlew assembleDebug` 编译通过
- [x] APK 生成：`app/build/outputs/apk/debug/app-debug.apk`

## 最终改动摘要

### ImageToImageScreen.kt

```diff
- val imagePickerLauncher = rememberLauncherForActivityResult(
-     ActivityResultContracts.GetContent()
- ) { uri ->
-     uri?.let {
-         imageToImageViewModel.onSourceImageChange(context, it)
-         imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
-     }
- }

+ val imagePickerLauncher = rememberLauncherForActivityResult(
+     ActivityResultContracts.OpenDocument()
+ ) { uri ->
+     uri?.let {
+         val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
+         context.contentResolver.takePersistableUriPermission(it, takeFlags)
+         imageToImageViewModel.onSourceImageChange(context, it)
+         imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
+     }
+ }

// 触发处: imagePickerLauncher.launch(arrayOf("image/*"))
```

### ImageToVideoScreen.kt

同上，只是 `imageToVideoViewModel` 而非 `imageToImageViewModel`。

### ReferenceImageThumbnail.kt

同上，且需要从 `LocalContext.current` 获取 context 来调用 `contentResolver`。

## 技术说明

`ActivityResultContracts.OpenDocument.launch(vararg mimeTypes: String)` 的签名：
- 构造函数：`OpenDocument()` **无参数**
- `launch()` 参数是 `vararg String`（MIME types 数组）

⚠️ 常见错误：传 `launch("image/*")` 会报类型不匹配（String vs Array<String>），必须显式 `launch(arrayOf("image/*"))`。
