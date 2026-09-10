## ADDED Requirements

### Requirement: Shared media component and isolated loading
系统 SHALL 先在 ui-components 提供 KMediaViewer，再由 kwiki 的源码卡片、预览和阅读页复用；媒体子入口 MUST 不加载无关组件，图片 MUST 不加载音视频引擎。

#### Scenario: Load media on demand
- **WHEN** 用户分别打开无媒体、仅图片、包含音视频的页面
- **THEN** 无媒体页不请求组件，图片页不请求 Plyr，音视频页按需加载播放器及其样式，不加载未使用的组件库功能

### Requirement: Stable loading and failure states
组件 SHALL 保持占位直到媒体就绪，图片 MUST 完整加载及解码后显示；解析、加载、超时错误 SHALL 可见并可重试，编辑器 SHALL 允许删除失败媒体。

#### Scenario: Slow image
- **WHEN** 图片 URL 已解析但尚未完成解码
- **THEN** 加载卡片保持存在，图片不可渐进露出，已知比例保持高度，未知比例最多一次尺寸收敛，不发生高度归零再恢复

#### Scenario: Expired source
- **WHEN** 媒体请求失败或超过默认 10 秒等待时间
- **THEN** 显示错误及重试，源码卡片可删除；切换源后的旧请求不能覆盖新状态

### Requirement: Configurable watermark and layout
组件 SHALL 支持水印文本、透明度、字号、颜色、角度和间距；图片 SHALL 默认居中且 auto 宽度为 50%，不超过容器。

#### Scenario: Watermarked fullscreen
- **WHEN** 开启水印后进入全屏
- **THEN** 水印仍在展示容器内且不阻挡操作；无法保持水印的原生全屏或 PiP 入口被替代或禁用

### Requirement: Honest download interaction policy
组件 SHALL 支持 allowDownload，默认 true；false SHALL 隐藏下载入口并限制组件区域右键下载交互，文档 MUST 明确其不能阻止 F12 或网络提取。

#### Scenario: Download disabled
- **WHEN** 调用方设置 allowDownload=false
- **THEN** 不展示或触发组件下载按钮，不将该值作为服务端授权依据，不宣称其能保护已传输字节

### Requirement: Application independent source resolution
组件 SHALL 通过调用方适配器解析稳定媒体引用，并清理过期异步任务；kwiki MUST 保留附件引用与现有访问鉴权。

#### Scenario: Save referenced media
- **WHEN** 用户保存已解析短链的媒体
- **THEN** 正文保留稳定附件引用，不保存临时签名 URL，音视频不进入文档索引
