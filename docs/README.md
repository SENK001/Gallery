# 文档索引与维护约定

本目录是项目的全部工程文档。原则：**代码是唯一事实来源**，文档与代码逐句同步；每件事只有一个「家」，同一内容不在多处重复。

## 文档地图

| 文档 | 回答什么问题 |
|---|---|
| [architecture.md](architecture.md) | 模块怎么分、依赖什么方向、启动流与关键设计决策是什么 |
| [data-layer.md](data-layer.md) | Provider 契约、MediaStore 抓取、相册归类、EXIF/XMP 规则 |
| [ui-guide.md](ui-guide.md) | 页面结构、insets 分配、权限/隐私流程、手势与状态切换、多选/选图/添加到 |
| [gl-rendering.md](gl-rendering.md) | 查看器/视频的 GL 几何、纹理、线程与异步管线；窗口层排查 |
| [external-api.md](external-api.md) | 外部调用契约：选图/大图 action、extras、结果、代理 URI 与临时授权 |
| [theme.md](theme.md) | 色板、styles 约定、`ThemeUtils`、颜色成对校验 |
| [build-and-release.md](build-and-release.md) | 工具链、ABI 变体、签名与 AK、APK 产物命名、依赖清单、GitHub Actions CI |
| [verification.md](verification.md) | 门禁命令、通过线、真机流程、GL 专项判据、本机基线数据 |
| [history.md](history.md) | 日期化的实测结论与缺陷根因（含结论被推翻的「更正记录」） |
| [backlog.md](backlog.md) | 未实现 / 未覆盖 / 明确不做的唯一收口 |
| README.md（本文件） | 文档地图与维护约定 |

阅读路线：先 [architecture.md](architecture.md) 建立全局，再按关心的方向进入 [data-layer.md](data-layer.md) / [ui-guide.md](ui-guide.md) / [gl-rendering.md](gl-rendering.md)；排查问题先读 [gl-rendering.md §7](gl-rendering.md) 与 [history.md](history.md)；对接外部调用读 [external-api.md](external-api.md)。

## 改动 → 需同步的文档

| 改动内容 | 同步 |
|---|---|
| 模块/依赖方向、启动流、跨模块约束 | [architecture.md](architecture.md) |
| Provider URI、契约列、查询语义、相册归类、自建相册/复制写入、EXIF/XMP 解析 | [data-layer.md](data-layer.md) |
| 新增/修改页面、insets、手势、权限与首启流程、多选/选图/添加到 | [ui-guide.md](ui-guide.md) |
| 查看器/视频的绘制、几何、纹理与线程 | [gl-rendering.md](gl-rendering.md) |
| 外部调用的 action/extras/结果、代理 URI 与授权 | [external-api.md](external-api.md) |
| 新增颜色或样式 | [theme.md](theme.md)（并跑 `scripts/check-color-pairs.py`） |
| 构建脚本、依赖版本、签名、AK、ABI、产物命名、CI 工作流 | [build-and-release.md](build-and-release.md) |
| 验证命令、通过线、基线数值 | [verification.md](verification.md) |
| 修复 bug / 得到新的实测结论 | [history.md](history.md) 追加日期条目 |
| 新限制、新待办、决定不做的事 | [backlog.md](backlog.md) |

## 写作约定

1. **只写当前状态**。被取代的旧结论进 [history.md](history.md)，不在现状文档里保留痕迹；plan 性质的内容不进 docs（做完了才有资格成为现状）。
2. **历史体例**：按日期正序，标题为 `## 日期 + 事件`；每条写清「背景 / 根因 / 修法 / 验证数据」。不用删除线或勘误体；结论后来被推翻时，在原条目内以「**更正记录**」子项写清结论如何变化。
3. **互引用相对链接**（`[数据层](data-layer.md)`），根 README 用 `docs/...` 前缀；不要悬空引用已删除的文件。
4. 全中文；代码路径/类名/参数用反引号；数据与判据尽量用表格或代码块。
5. 根 `README.md` 只保留入口内容（简介、功能概览、快速开始、文档索引、许可），细节一律下沉到本目录。
6. 代码注释引用文档时，直接指向本目录的具体文件；旧开发计划文档已拆分删除，不要再引用。

核对基准：2026-10-19 已按当前工作区代码逐句核对。
