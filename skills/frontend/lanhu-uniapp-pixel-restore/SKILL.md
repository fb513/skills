---
name: lanhu-uniapp-pixel-restore
description: Restore Lanhu UI designs into the Fuying uni-app WeChat Mini Program with high visual fidelity. Use for 蓝湖设计稿还原, 高保真还原, 像素级对齐, converting Lanhu or design-tool output into maintainable uni-app pages, uploading downloaded slices to OSS with the repository upload script, and completing visual check/retry loops without copying absolute-positioned scaffolds.
---

# Lanhu Uniapp Pixel Restore

用于把蓝湖设计稿高保真落到本仓库的 uni-app 小程序页面。执行时必须使用当前环境可用的设计读取、资源获取、预览、截图和验证能力。除本仓库指定的 OSS 上传脚本外，Skill 文档不得绑定具体工具名、命令名或 MCP 调用名。

详细执行细则见 [references/uniapp-pixel-restore.md](references/uniapp-pixel-restore.md)。实现前只读取与当前任务相关的部分；如果没有可用的设计读取、资源获取或截图能力，按本文的降级规则交付风险说明。

## 不可违反

- 不得直接复制设计工具导出的整页绝对定位骨架、逐层坐标、无语义 DOM、固定堆叠层级或为贴图生成的容器。
- 不得把设计导出的 HTML 结构当成最终页面结构；它只能作为视觉规格、资源裁剪和结构推断证据。
- 不得使用临时远程资源、失效外链、emoji、临时 SVG 或明显占位内容冒充设计资源；最终页面只能引用稳定 OSS 资源或项目既有资源。
- 不得在明显视觉偏差未归因、未修正、未复查时结束任务。
- 不得为了对齐单一截图滥用负间距、无语义位移、固定坐标或页面级定位。
- 不得只还原页面内自定义导航而遗漏 `src/pages.json` 页面配置；`navigationBarTitleText`、`navigationStyle`、`backgroundColor` 等页面级配置必须与设计标题、导航形态和页面底色同步。

## 强制流程

1. 获取设计证据：取得画布尺寸、页面层级、HTML/CSS 或等价结构化标注、Design Tokens、设计截图和切图资源清单。
2. 建立规格账本：记录页面背景、顶部区域、模块边界、组件尺寸、文本规格、颜色、间距、圆角、阴影、渐变、图片裁剪、状态差异和资源来源。
3. 资源上传：下载所有设计图片、图标和切图后，必须使用 `scripts/upload-image-to-oss.cjs` 上传到 OSS，并记录本地源文件、脚本输出的 OSS `url`、尺寸和使用位置；页面代码引用 OSS 地址，不引用下载后的本地切图路径。
4. 推导语义布局：从坐标和层级中推断模块、列表、按钮、Tab、卡片、角标、浮层、图片裁剪和状态，而不是复制导出结构。
5. 实现页面：遵守本仓库 Vue 3、uni-app、TypeScript、Tailwind token、中文文案、图标体系、导航封装和资源路径约定；同时更新 `src/pages.json` 中目标页面的 `navigationBarTitleText`、`navigationStyle`、`backgroundColor` 等页面级配置。
6. 预览验证：使用当前环境最强可行方式进行构建、运行、截图或视觉复查；优先验证目标小程序环境，H5 只作为等价视觉检查。
7. 偏差闭环：发现偏差时先归因，再修正并重新验证受影响项。
8. 交付说明：说明完成项、验证方式、无法验证项、设计取舍和剩余风险。

## 证据优先级

1. HTML/CSS 或等价结构化标注中的视觉属性值是主要规格来源。
2. Design Tokens 只补足 CSS 或标注缺失的渐变、边框、阴影、透明度等细节。
3. 设计截图用于视觉复核，不能覆盖明确的结构化规格值。
4. 现有项目 token 优先用于常规页面规范；颜色、阴影、圆角和字号先映射到 `DESIGN.md`、`tailwind.config.js`、`src/styles/design-tokens.scss` 中的 token。
5. 像素还原关键值没有等价 token 时，允许页面局部样式使用换算后的设计精确值；颜色值必须优先用页面局部 CSS 变量承接，避免裸 `#hex` / `rgb(a)` 散落在业务样式中，并在规格账本记录原因。

## 单位与布局

- 先确认设计画布宽度，再把设计 px 按目标小程序宽度换算为 rpx；不要未经确认套用经验比例。
- 375px 设计画布才可等价使用 `px × 2 = rpx`；其他画布必须使用 `px × 750 / 画布宽度`，并在规格账本记录换算比例。
- 关键宽高、间距、字号、行高、圆角、阴影和图片裁剪都必须能追溯到设计证据。
- 页面必须适应真实机型、动态内容、安全区和业务状态。
- 只有浮层、角标、吸顶、遮罩、动画、图片裁剪、组件限制等有明确语义的元素，才可以使用定位、层级、位移、固定尺寸或裁剪。

## 验收标准

- 页面能在当前最接近目标平台的环境运行，没有明显构建、语法、类型或资源错误。
- 代码没有复制设计工具的绝对定位骨架、导出 DOM 或固定层级。
- 背景、顶部区域、模块边界、卡片、Tab、按钮、列表、文字、图标、图片裁剪和主要间距与设计证据一致。
- `src/pages.json` 中目标页面的 `navigationBarTitleText` 必须与设计标题或页面内自定义标题一致；使用自定义导航时必须配置 `navigationStyle: "custom"`，页面级 `backgroundColor` 必须与设计页面底色一致。
- 关键尺寸、样式、间距和状态已逐项验证；不能验证的项必须逐项说明。
- 设计切图已经通过 `scripts/upload-image-to-oss.cjs` 上传到 OSS，代码引用脚本输出的 OSS 地址，没有临时远程设计资源、本地下载切图路径或占位资源。
- 改动范围聚焦在还原目标，没有引入无关业务变化或额外视觉风格。
- 最低验证必须包含：`npm run build:mp-weixin`、OSS 上传结果检查、新增切图引用检查、无远程设计资源检查；具备浏览器能力时补充 H5 截图返查；修改完成后运行 `graphify update .`。

## H5 截图能力

H5 截图不需要设计读取能力配合；设计读取能力只负责提供设计规格、截图和资源。H5 视觉验证需要的是浏览器自动化能力：能打开本地或临时预览地址、设置视口尺寸、等待页面稳定、截图、必要时读取页面尺寸和资源加载状态。

如果当前环境没有浏览器自动化能力，可以降级为构建检查、静态规格核对和人工可复现的预览说明；交付时必须说明未完成截图比对以及相关风险。
