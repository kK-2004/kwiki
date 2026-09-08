## ADDED Requirements

### Requirement: Images and playable media render during editing
系统 SHALL 支持标准 Markdown 图片与受限 img/audio/video 媒体标记，在编辑文中、阅读、修订预览和导出中一致呈现。编辑时 MUST 同时允许周围文本输入与媒体实际预览，不能仅通过退出编辑进入独立预览来满足需求。

#### Scenario: Standard image markdown is entered
- **WHEN** 用户输入 `![架构图](https://example.com/image.png)`
- **THEN** 文中显示图片和替代文字语义，不渲染成带感叹号的普通链接

#### Scenario: Markdown contains escaped or complex syntax
- **WHEN** 源码包含转义图片标记、URL 括号、代码中的媒体文字或不完整链接
- **THEN** 按 Markdown 语义解析，代码/转义内容保持文字，不猜测或执行错误链接

#### Scenario: Audio or video is inserted
- **WHEN** 文中存在受支持音频/视频节点
- **THEN** 显示可操作播放器、controls 和非自动播放预览，浏览器不支持或加载失败时显示可读占位/链接

### Requirement: Upload and existing-link menus use content center
图片、音频、视频工具栏菜单 SHALL 依次提供「本地上传」「已有链接」。本地上传 MUST 通过后端现有内容中心 SDK，校验类型/大小/权限，返回持久附件引用；已有链接仅允许 http/https，服务端不得主动抓取任意外链。

#### Scenario: Local media upload completes
- **WHEN** 上传成功且通过内容验证
- **THEN** 通过 SDK 存储 fileId，在原选区插入稳定 attachment 引用并预览，不在正文持久化临时签名 URL

#### Scenario: Upload fails or user changes page
- **WHEN** 上传失败、用户移除占位或切换页面
- **THEN** 明确反馈状态，不插入空 URL 或错页面位置，不留下伪成功节点；失败可重试/删除

#### Scenario: Publish with current draft
- **WHEN** 用户编辑文字和媒体后直接点击发布
- **THEN** 先保存当前有效草稿再发布该修订；尚有进行中的媒体上传时阻止发布并说明等待原因

### Requirement: Only image attachments enter the document index
系统 MUST 仅允许经过实际内容/MIME 验证的图片附件进入文档索引链路。音频、视频、PDF、Office 等其他附件 SHALL 仅在页面展示，不生成附件检索 chunks、embedding 或 ES upsert；该规则 MUST 覆盖上传、重试、worker 和恢复重建。Wiki 页面正文仍按既有发布规则索引。

#### Scenario: Image attachment is uploaded
- **WHEN** 受支持图片通过验证并经内容中心 SDK 上传成功
- **THEN** 系统创建图片文档索引任务，解析后的可检索内容受既有权限、子/父 chunk 和生命周期规则约束，解析失败明确标记而不伪报成功

#### Scenario: Non-image attachment is uploaded
- **WHEN** 用户上传音频、视频、PDF、Office 或其他非图片附件
- **THEN** 页面可以展示对应播放器或文件链接，但系统不解析该附件内容、不生成向量、不将其作为附件证据召回

#### Scenario: Legacy non-image attachment indexing work exists
- **WHEN** 上线时存在非图片附件旧 chunks，或旧 upsert/重试/恢复任务再次执行
- **THEN** 清除对应历史附件 chunks，阻止新写入，保留附件文件、元数据和页面展示引用

#### Scenario: Explicit import produces a Wiki page
- **WHEN** 用户将非图片文件显式导入为 Wiki 页面并发布
- **THEN** 生成的页面正文按页面规则索引，源非图片附件不另建附件索引

#### Scenario: Image is inserted using an external link
- **WHEN** 用户只填写外部图片链接而未上传为受管理附件
- **THEN** 页面直接展示图片，服务端不为附件索引自动抓取任意外链

### Requirement: Media layout and dimensions survive round trips
媒体节点 SHALL 在 hover/focus 时右下角显示布局与大小控件，触屏支持点选；支持 left/center/right、自动大小、25/50/75/100% 和 80–1920 px 自定义宽度。图片/视频 MUST 保持比例并限制容器宽度，音频调整播放器宽度；配置写回受控 Markdown 属性。

#### Scenario: User centers and resizes an image
- **WHEN** 用户选择居中和 50% 宽度
- **THEN** 立即预览，配置在保存、发布、重新打开、历史查看后仍在，布局控件不永久遮挡内容

#### Scenario: User edits one media node
- **WHEN** 用户修改尺寸后撤销或重做
- **THEN** 只变更对应媒体源码范围，未修改 Markdown 保真，光标/选区和撤销状态保持正确

#### Scenario: Input contains conflicting or unsafe attributes
- **WHEN** 用户输入任意 CSS、事件属性、越界尺寸或同时百分比/固定宽度
- **THEN** 渲染器拒绝危险属性并按规范归一化尺寸，不能执行脚本或撑破容器

### Requirement: Authorized preview uses fresh links and safe rendering
系统 MUST 校验附件与实际页面/修订引用权限，用 SDK 新链接提供预览，且图片、音频、视频共用白名单安全渲染。系统 SHALL 禁止 javascript/file/data、任意 iframe/script/事件属性，并支持授权短链接续取和播放器能力验证。

#### Scenario: Preview URL expires
- **WHEN** 用户重新打开页面或播放器链接过期
- **THEN** 根据稳定附件身份重新授权取链接，源码不变，失败明确提示而非覆盖存储内容

#### Scenario: Attachment belongs to an inaccessible resource
- **WHEN** 用户构造别人的 attachment UUID 或从已归档页面请求预览
- **THEN** 服务端拒绝发放链接，前端显示不可用占位

#### Scenario: Media contract is validated
- **WHEN** 验收内容中心图片、音频、视频预览
- **THEN** 验证 Content-Type、短链有效期和音视频 Range/拖动能力；能力缺失时记录具体不支持项，不能伪报通过

### Requirement: Code and ordered-list commands operate on selection
编辑器 SHALL 提供可用的代码块和序号按钮，代码块支持选区包裹/光标插入及语言，序号支持连续编号、换行续号与撤销；已有标题/加粗 SHALL 使用同一编辑命令契约。

#### Scenario: Selected code contains backticks
- **WHEN** 用户通过按钮包裹含反引号的文本
- **THEN** 生成足够长的 fenced code block，代码保持字面内容并正确预览

#### Scenario: Selected lines become numbered items
- **WHEN** 用户选择多行点击序号
- **THEN** 选中行形成连续有序列表，操作可撤销，未选中文本保持不变

### Requirement: Markdown and HTML export preserve content and media
系统 SHALL 提供 Markdown/HTML 一键导出，编辑态导出当前草稿快照、阅读态导出当前查看修订；不隐式发布。有上传媒体时 MUST 生成包含所选格式文件和去重 assets 的 ZIP 并使用相对引用，无本地媒体直接导出 `.md`/`.html`，外链保持原样。

#### Scenario: User exports an unsaved draft
- **WHEN** 用户在编辑器选择 Markdown 或 HTML 导出
- **THEN** 导出包含当前未保存文字与有效媒体配置，Wiki 发布状态不变，HTML 有 UTF-8 和必要安全样式

#### Scenario: Uploaded media is exported
- **WHEN** 正文引用内容中心附件
- **THEN** 后端授权后通过 SDK 有界读取并打包实际媒体，相对路径可用，不嵌入过期签名 URL；UI 明确含媒体时打包

#### Scenario: Required export media cannot be fetched
- **WHEN** 权限变更、附件读取失败或总大小/时限超限
- **THEN** 导出明确失败且可重试，不交付标称完整却缺资源的文件，不下载任意外链，也不暴露路径/密钥
