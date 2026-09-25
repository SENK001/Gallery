package com.senk.gallery

import android.app.Application

/**
 * 应用入口。
 *
 * **这里刻意不初始化百度地图 SDK**：SDK 初始化统一收敛在
 * `com.senk.gallery.ui.BaiduMapSdk.ensureInitialized()`，并在所有创建地图 View
 * 或调用逆地理编码的路径之前显式调用（`ViewerActivity.ensureSheetMap()`、
 * `PhotoMapActivity.onCreate()`、`LocationAddressResolver.ensureInitialized()`）。
 * 该方法会先检查 `PrivacyPrefs.isAgreed()`，**用户未同意隐私政策时不会初始化 SDK**。
 *
 * 首启流程由 `PrivacyActivity`（LAUNCHER）把关：未同意不进入主界面。
 * 同意状态由 `com.senk.gallery.ui.PrivacyPrefs` 持久化。
 *
 * 早期实现曾在 `onCreate` 里直接 `SDKInitializer.setAgreePrivacy(this, true)` +
 * `initialize(this)`，有两个问题：
 *
 * 1. **隐私合规**：在用户看到任何隐私提示之前就代替用户「同意」。现已由首启同意页解决。
 * 2. **坐标系**：`initialize` 一旦在这里完成，`LocationAddressResolver` 里的
 *    `setCoordType(CoordType.BD09LL)` 会因为 `SDKInitializer.isInitialized()` 已是 true
 *    而**永远不会执行** —— SDK 退回默认的 GCJ-02，而送入的却是 BD09LL 坐标，
 *    地图与逆地理编码结果整体偏移。初始化点唯一化后该问题不复存在。
 */
class GalleryApp : Application()
