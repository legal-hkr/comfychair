# Task Plan: ComfyChair 图生图多图片选择支持

## Goal
为 ComfyChair 图生图（Editing 模式）添加多图片输入支持，同时修复 Workflow JSON 中 `{{upscale_method}}` 和 `{{ckpt_name}}` 占位符未替换导致的 Server Error 400 报错。

## Current Phase
Phase 4 — UI 重构：HorizontalPager + 动态 TabRow

## Phases

### Phase 1: 调研与问题定位
- [x] 确认多图片选择的现有实现范围（UI + 上传 + Workflow 占位符）
- [x] 定位 `{{upscale_method}}` 占位符在哪个 workflow JSON 文件中
- [x] 定位 `{{ckpt_name}}` 条件替换问题的根因
- [x] 确认 Editing 模式的 workflow JSON 结构（哪些节点需要多图片输入）
- [x] 确认 `prepareEditingWorkflow` 中 sourceImage2/3/4 的处理链路
- **Status:** ✅ complete

### Phase 2: 修复 Server Error 400 Bug（提交前预检验）
- [x] 在 `prepareWorkflow()` 返回前，扫描 workflow JSON 中的剩余 `{{...}}` 占位符
- [x] 如果发现未解析的占位符，通过 `_events.emit(ImageToImageEvent.UnresolvedPlaceholders(...))` 提示用户
- [x] 用户确认后才允许提交（或引导用户去设置对应参数）
- [x] Nick 确认此方案后实施
- **Status:** ✅ complete（2026-04-29，实施完成，编译通过）

### Phase 3: 完善多图片输入支持
- [x] 检查 UI 层面 sourceImage2/3/4 的选择器是否已实现 → ✅ 已存在 AdditionalImageSlot
- [x] 确认 `image_filename_2/3/4` 占位符在 workflow JSON 中的存在位置 → ✅ 占位符由 WorkflowManager 解析
- [x] 确认 `prepareEditingWorkflow` 对 sourceImage2/3/4 的处理是否完整 → ✅ 已有完整替换逻辑
- [x] **动态槽位显示** — UI 根据 workflow 绑定的 `{{image_filename_2/3/4}}` 占位符数量动态显示槽位
- **Status:** ✅ complete（2026-04-29，实施完成，编译通过）

### Phase 4: UI 重构 — HorizontalPager + 动态 TabRow（Nick 确认方案）
> Nick 要求：把原图/预览的 SegmentedButtonRow + AdditionalImageSlot Row 替换为横向滑动 Pager

#### Phase 4.1: 添加依赖
- [x] 在 `libs.versions.toml` 添加 `compose-foundation`（含 `HorizontalPager`）
- [x] 在 `build.gradle.kts` 添加 `implementation(libs.composeFoundation)`
- [x] 编译验证依赖正常
- **Status:** ✅ complete

#### Phase 4.2: 导入检查
- [x] 确认 `HorizontalPager`, `PagerState`, `TabRow`, `Tab` 在 `ImageToImageScreen.kt` 中可用
- [x] 新增缺失的 import
- **Status:** ✅ complete

#### Phase 4.3: PagerState + 状态变量
- [x] 引入 `PagerState`（`rememberPagerState`）
- [x] 用 `pagerState.currentPage` 作为唯一 `selectedPage` 来源（双向绑定：Tab 点击 → `scrollToPage`，Pager 滑动 → 自动更新）
- [x] 页面列表动态生成：`[原图1, 原图2?, 原图3?, 原图4?, 预览]`（根据实际存在的图片）
- **Status:** ✅ complete

#### Phase 4.4: TabRow 替换 SegmentedButtonRow
- [x] 删除 `SingleChoiceSegmentedButtonRow` + 两个 `SegmentedButton`
- [x] 替换为 `TabRow(selectedTabIndex = pagerState.currentPage)`
- [x] 动态生成 `tab` 列表：`["原图1", "原图2", "原图3", "原图4", "预览"]`（只显示有图片的 tab）
- [x] `Tab` 点击时调用 `scope.launch { pagerState.scrollToPage(index) }`
- **Status:** ✅ complete

#### Phase 4.5: Image Preview Box → HorizontalPager
- [x] 删除原来的 `Image Preview Box`（第 342-440 行）
- [x] 替换为 `HorizontalPager(state = pagerState, modifier = Modifier.weight(1f))`
- [x] 每页内容：根据 `page` 索引显示对应图片
  - page 0-3: sourceImage 1-4（如有）
  - last page: previewImage
- [x] 点击图片 → 触发 MediaViewer（对应 filename/subfolder）
- **Status:** ✅ complete

#### Phase 4.6: 删除 AdditionalImageSlot Row
- [x] 删除第 497-535 行的 AdditionalImageSlot Row（已整合进 Pager）
- [x] 清理 `imagePickerLauncher2/3/4`（仍然需要，用于 Pager 内各 slot 的图片选择）
- **Status:** ✅ complete

#### Phase 4.7: 编译 + 测试
- [x] `./gradlew :app:assembleDebug` 编译通过
- [x] 提供 APK 直链给 Nick 测试
- **Status:** ✅ complete

### Phase 5: 测试验证
- [ ] 在编辑模式下选择多张图片，触发生成，验证不报 400
- [ ] 验证每张图片都正确上传到 ComfyUI
- [ ] 验证生成的图片符合预期
- **Status:** pending

### Phase 6: 交付
- [ ] 整理修改的文件清单
- [ ] 总结调研结果和修复内容
- **Status:** pending

## Key Decisions
| Decision | Rationale |
|----------|-----------|
| 提交前预检验 + 用户确认对话框 | 在 ComfyUI 返回 400 之前拦截；用户能明确知道哪个参数未设置 |
| 不在 `WorkflowManager` 中为每个占位符添加默认值 | 会掩盖配置不完整的问题，用户无法知情 |
| PagerState.currentPage 作为唯一 selectedPage 来源 | Tab 点击同步 Pager；Pager 滑动自动更新 Tab（PagerState 内置观察） |
| 动态 page count | 只生成有实际图片的页面；预览永远是最后一页 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Server Error 400: `{{upscale_method}}` not in list | 1 | Phase 2 预检验方案 |
| slots aspectRatio(1f) 顶掉预览框 | 1 | 改为 80dp 固定高度 |
| slots Row 位置在预览框上方 | 2 | 移到 TextField 下方（仍不够好，最终方案 HorizontalPager） |
