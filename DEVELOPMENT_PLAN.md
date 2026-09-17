# 通用 Android 云端智能记账应用开发计划

> 本文档是后续开发与 AI 协作的主约束。任何涉及权限、图片来源、云端传输、数据存储、自动入账阈值或删除行为的调整，都必须先更新本文档并说明风险。

## 1. 产品目标与范围

开发一款面向个人使用、源码公开、支持通用 Android 的智能记账应用。最低支持 `minSdk 26`；小米/HyperOS 仅作为兼容性测试设备之一，不构成产品支持边界。

首版目标：

- 自动识别微信、支付宝等支付页面中的支出、收入和退款。
- 通过标准 Android MediaStore/URI 接口发现最近截图，并逐张读取本地照片。
- 用户明确启用云端识别后，将当前照片发送到用户配置的 OpenAI 兼容 VLM 服务。
- VLM 返回严格版本化的账单 JSON；客户端验证后仅将高置信度结果自动入账，其余进入待确认列表。
- 识别完成后显示一次性结果提示；允许修改标签、保留截图或请求系统删除截图。
- 按固定月预算计算每日参考预算、当日结余/超支和标签统计。
- 支持微信、支付宝官方账单导入，用于补漏、纠错、去重和最终对账。
- 支持 CSV 与 XLSX 月报导出。

云端识别是可选能力。手工记账、历史账本、预算、导入和导出在无网络时仍可用；照片自动识别在无网络、未配置服务或服务失败时进入待处理/待确认状态，不得伪造成功结果。

不在首版实现：

- 读取短信、联系人、剪贴板或全部通知内容的通用识别。
- 持续遍历微信、支付宝的无障碍控件树。
- MediaProjection 常驻录屏、Root、Shizuku 或绕过 `FLAG_SECURE`。
- 自动操作支付按钮、输入密码或通过 `dispatchGesture()` 模拟付款操作。
- 应用账号体系、广告、遥测、云同步和服务端长期保存原图。
- 把 APK 混淆当作 API Key 的安全边界。

## 2. 技术栈与工程结构

- Kotlin、Gradle Kotlin DSL、Jetpack Compose、Material 3、Coroutines/Flow。
- `minSdk 26`；`compileSdk` 和 `targetSdk` 使用建项时最新稳定版本。
- 单 `app` 模块，按 `ledger`、`detection`、`photo`、`vlm`、`overlay`、`reconciliation`、`report`、`security`、`ui` 分包。
- Room 保存账本；金额统一使用 `Long` 表示最小货币单位，禁止使用浮点数处理金额。
- SQLCipher 加密数据库；随机数据库密钥由 Android Keystore 的 AES-GCM 密钥封装。
- `SharedPreferences` 仅保存非敏感设置；API Key 单独使用 Android Keystore 加密保存；关闭 Android 自动备份和设备间数据迁移。
- 使用 Kotlin serialization 或同等结构化 JSON 库解析 VLM 响应；禁止用正则从自然语言中提取账目。
- CSV 使用成熟的结构化读写库；XLSX 使用流式生成库，避免大月报占用过多内存。
- 图片发现、VLM 请求、导入、对账和报表生成在后台协程或 WorkManager 中执行，不阻塞 UI。
- 后台自动处理使用独立且默认关闭的用户开关；启用后由带联网约束的 WorkManager 每 15 分钟兜底扫描，并由前台 MediaStore 事件触发一次性唯一任务。不得宣称进程常驻或保证秒级执行。
- 后台合法但未达到自动入账条件的结果写入加密数据库待确认表；自动入账静默保存，待确认、权限、配置及最终失败通过低优先级通知提示。
- 文件选择与导出统一使用 Storage Access Framework。

核心接口：

```kotlin
interface ScreenshotDetector {
    fun events(): Flow<ScreenshotCandidate>
}

interface LocalPhotoReader {
    suspend fun read(candidate: ScreenshotCandidate): PhotoReadResult
}

interface VlmClient {
    suspend fun analyze(request: VlmRequest): VlmResponse
}

interface LedgerDecisionEngine {
    suspend fun decide(response: VlmResponse, evidence: PhotoEvidence): LedgerDecision
}

interface StatementImporter {
    suspend fun import(uri: Uri): ImportResult
}
```

`ScreenshotDetector`、`LocalPhotoReader`、`VlmClient` 和 `LedgerDecisionEngine` 必须与 UI、Service 和具体供应商解耦。VLM 请求/响应字段和系统 Prompt 以 [`VLM_API_AND_PROMPT.md`](VLM_API_AND_PROMPT.md) 为唯一事实来源。

## 3. 照片发现与本地读取

### 3.1 触发路径

标准 Android 实现按以下顺序工作：

1. 监听 MediaStore 新增/变更事件，或由用户点击“识别最近截图”手动触发。
2. 可选使用 `NotificationListenerService` 作为截图完成提示，但通知不是交易证据，且不能替代 MediaStore 查询。
3. 在有限时间窗口内查询 MediaStore，寻找已经写入完成的最近截图。
4. 按候选顺序逐张读取并提交云端 VLM；单张失败不应阻塞其他候选。

不硬编码厂商 SystemUI 包名、频道、截图路径或文件名。截图候选依据 MediaStore 元数据、目录/显示名称、MIME、时间窗口、尺寸和方向等稳定条件判断。

### 3.2 Android 版本权限

- Android 13（API 33）及以上申请 `READ_MEDIA_IMAGES`；API 33 按允许或拒绝处理。
- Android 14（API 34）及以上同时识别 `READ_MEDIA_VISUAL_USER_SELECTED` 的部分照片授权；仅获部分授权时关闭自动发现，并保留系统文件选择器入口。
- Android 12（API 32）及以下申请 `READ_EXTERNAL_STORAGE`，仅在对应 API 级别使用。
- 不申请 `MANAGE_EXTERNAL_STORAGE`；不通过 Root 或隐藏 API 绕过权限。
- 用户未授予足够照片权限时，关闭自动发现，保留系统分享入口或用户逐张选择入口，并在界面说明原因。
- 首次启用云端识别时单独征得“将当前照片发送到第三方 VLM 服务”的明确同意。

### 3.3 候选过滤与原位读取

候选照片必须同时满足：

- MediaStore 创建/修改时间位于触发窗口内；
- `IS_PENDING = 0`，文件可完整读取；
- MIME 为受支持的图片类型，尺寸和方向符合合理范围；
- URI、文件大小和 SHA-256 内容哈希未处理过；
- 根据目录/显示名称等元数据符合最近截图条件。

使用 `ContentResolver.openInputStream()` 或文件描述符读取。编码、尺寸限制和网络请求所需的临时缓冲只能存在于内存中；不得复制到应用数据库、缓存目录或其他持久化位置。读取完成后立即关闭流并释放缓冲。

处理状态至少包括 `PENDING`、`PROCESSING`、`AUTO_BOOKED`、`NEEDS_CONFIRMATION`、`REJECTED`、`RETRYABLE_ERROR` 和 `FAILED`。网络超时、限流和临时服务错误使用有限次数退避重试，禁止持续轮询或无限重试。

## 4. 云端 VLM 识别与入账决策

### 4.1 请求边界

- 使用 HTTPS 的 OpenAI 兼容接口；生产环境禁止明文 HTTP。
- 前端只提供 `baseUrl`、`apiKey`、`model`、请求超时和启用开关等表单字段，不提供原始 JSON 编辑器。
- API Key 由用户输入，使用 Android Keystore 加密保存，界面掩码显示，任何日志和诊断包都必须脱敏。
- 每次只发送当前候选照片及固定 Prompt；不发送账本数据库、完整日志、其他照片或设备无关数据。
- 服务端应承诺并配置为处理完成后立即删除原图和临时文件；客户端不依赖服务端长期缓存，但无法替任意第三方端点验证其实际留存行为，用户启用前必须核对服务条款。

### 4.2 响应校验

VLM 必须返回 `ledger.v1` 严格 JSON，至少包含：

- `decision`、`is_payment_screenshot`、`platform`、`direction`；
- `amount_minor`、`currency`、`occurred_at`、`time_source`；
- `merchant`、`counterparty`、`external_id`、`suggested_tag`；
- `confidence` 和 `evidence`（正特征、负特征、新鲜度、原因码）。

缺失、类型错误、未知版本、非法枚举、非法金额、自然语言前缀、Markdown 或无法解析的 JSON 均视为无效响应，结果只能进入待确认或失败状态。客户端不得仅依据 VLM 的 `decision` 字段自动入账。

### 4.3 自动入账阈值

客户端账本引擎使用固定阈值 `confidence >= 0.90`，并且必须同时满足：

- 平台、方向、金额和交易时间字段完整；
- 页面成功状态明确，金额唯一；
- 无强负特征（聊天、账单列表、历史详情、分享海报、图片预览等）；
- 新鲜度有效，或有明确的通知/账单匹配；
- 本地内容哈希和交易指纹未重复。

否则进入待确认列表；模型无法证明是支付页面时直接拒绝。高置信度也不代表已与官方账单对账，只有匹配成功后才标记为 `RECONCILED`。

### 4.4 时间与审计

每个识别事件保存：

- `screenshotCapturedAt`：MediaStore 可用的图片生成时间；
- `detectedAt`：应用发现并开始处理的时间；
- `pageTransactionAt`：页面识别出的交易时间，可为空；
- `occurredAt`：最终用于账本的时间；
- `timeSource`：`PAGE_EXACT`、`NOTIFICATION_MATCHED`、`SCREENSHOT_ESTIMATED`、`STATEMENT_VERIFIED` 或 `USER_CONFIRMED`；
- VLM 服务标识、模型版本、请求状态、规则/Prompt 版本、不可逆图片哈希和交易指纹。

没有页面时间时，只能使用截图时间并标记 `SCREENSHOT_ESTIMATED`，不得伪装成精确交易时间。所有自动入账、人工修改、失败重试、账单匹配和删除请求都写入审计事件。

## 5. 设置、结果提示与照片删除

- 设置页使用普通表单输入 `Base URL`、`API Key`、`Model`、超时和云端识别开关，并提供连接测试；不显示或允许编辑原始请求 JSON。
- 连接测试不得上传真实照片，只发送供应商允许的最小探测请求。
- 不显示常驻浮钮。识别完成后才创建一次性 `TYPE_APPLICATION_OVERLAY`，约 8 至 10 秒后移除；无浮层权限时降级为高优先级通知。
- 高置信度提示显示“已录入金额 · 标签”，操作为“修改”“删除截图”“保留”。
- 非高置信度提示显示“检测到疑似支付截图”，操作为“确认录入”“忽略”；不得显示“已录入”。
- 只有账目成功持久化后才提供删除入口。
- Android 11（API 30）及以上调用 `MediaStore.createDeleteRequest()` 由系统确认；低版本使用对应的用户可见删除流程。应用不得静默删除非自身创建的照片。
- 用户取消删除、系统拒绝或识别失败时默认保留原图；账目和脱敏证据不受影响。

## 6. 账本、预算与对账

核心数据：

- `Transaction`：UUID、类型、金额、币种、交易时间、商户、平台、标签、状态、置信度、外部流水号、去重指纹及创建/修改时间；
- `TransactionEvidence`：来源、截图时间字段、VLM 服务/模型、Prompt 版本、匹配特征、置信度、图片哈希和对账状态，不保存原始截图或完整 OCR 文本；
- `PhotoProcessing`：URI、文件大小、哈希、处理状态、重试次数、错误码和时间戳；
- `Tag`：名称、颜色、排序和启用状态，不包含预算额度；
- `MonthlyBudget`：年月与总预算，每月只有一个生效预算；
- `ImportBatch`：平台、文件哈希、导入时间、统计结果和撤销状态；
- `AuditEvent`：自动识别、人工修改、账单匹配、时间校正、网络失败和删除请求记录。

预算规则：

- 固定日预算等于当月总预算除以当月自然日数；
- 当日预算结余等于固定日预算减去当日净支出；
- 净支出等于支出减退款；普通收入不抵扣预算，另行展示现金流；
- 标签仅统计金额、占比和趋势，不设置分类预算，也不改变日预算。

账单对账：

- 用户通过系统文件选择器导入自行取得的微信或支付宝官方账单；
- 流水号优先精确匹配；无流水号时按平台、方向、金额、规范化商户和时间窗口匹配；
- 唯一匹配时补充字段并标记已对账；缺失记录补录；多候选和冲突进入对账页；
- 同一文件通过文件哈希防止重复导入，整个导入批次可撤销；
- 账单记录可以校正截图估算时间，但不得无审计记录地覆盖用户手工修改。

## 7. 诊断日志与隐私

默认日志使用固定大小环形缓冲区，保存不超过 7 天，只记录：

- 应用版本、设备型号、Android API/版本和权限状态；
- MediaStore 查询阶段、图片尺寸/方向、不可逆哈希、耗时和错误码；
- VLM 服务域名（去除路径和凭据）、模型名、请求状态、耗时和响应错误类别；
- Prompt/契约版本、特征 ID、最终决策原因和重试次数。

不得记录 API Key、Authorization Header、Base64 图片、图片路径中的用户信息、完整 OCR/VLM 文本、商户、金额、流水号或原图。用户主动导出诊断包时先展示内容预览和脱敏提示。

测试样本使用合成数据或彻底脱敏图片；不得把真实支付照片、账单、数据库、签名密钥或令牌提交到 GitHub。

## 8. 图片样本要求

每个平台应准备脱敏正负样本：

- 支付成功、收款成功、退款成功、转账、扫码和小程序页面；
- 深色/浅色模式、不同字体大小和屏幕缩放；
- 带交易时间、不带交易时间、旧交易时间和多金额页面；
- 账单列表、月度账单、交易详情、聊天支付截图、分享海报、图片预览；
- 失败、处理中、待支付和取消页面。

样本应去除真实姓名、头像、二维码、条码、订单号和商户隐私。金额可以使用合成值。仓库只保存合成或彻底脱敏的夹具。

## 9. 测试与验收

- API 26、33 和最新稳定 API 的照片权限、MediaStore 查询、URI 读取和删除确认测试；
- 至少一台 AOSP/主流 Android 设备和一台 HyperOS 设备的端到端测试；
- 逐张读取、输入流关闭、哈希幂等、重复变更事件和大图内存限制测试；
- `ledger.v1` 合法/缺字段/错误类型/未知版本/自然语言前缀/非法金额响应测试；
- 聊天截图、账单列表、历史详情、多金额、缺失时间和旧交易不得自动入账；
- 高置信度唯一支付页面只生成一条账目；
- 401、403、429、超时、断网、5xx、空响应和模型拒答测试；
- API Key Keystore 存储、掩码显示、连接测试不上传真实照片和日志脱敏测试；
- 自有或 Mock VLM 服务处理完成后删除原图的契约测试；对任意第三方端点只验证启用前告知和用户确认，不声称客户端能够验证服务商实际留存行为；
- 入账后删除弹窗、取消删除、系统确认和低版本降级流程测试；
- 官方账单导入去重、冲突、撤销和时间校正测试。

验收标准：

- 在获得照片权限和用户云端同意后，无需系统分享即可发现最近截图并逐张处理；
- 断网、未配置服务或云端失败时不生成伪造账目，任务可重试或人工确认；
- 高置信度支付页面只生成一条正确账目，聊天/账单/分享图片不会自动入账；
- 截图时间、页面交易时间、云端处理状态和决策原因可审计；
- 用户删除截图后，账目和脱敏证据仍存在，设备上没有应用复制的原图；
- API Key 不出现在日志、诊断包、备份或版本库中；
- 规则或 Prompt 失效时可通过脱敏样本和 Mock VLM 稳定复现并修复。

## 10. 实施顺序与 AI 操作重点

1. 创建通用 Android 工程、加密 Room、手工收支、标签和月/日预算。
2. 实现 API 26+ 的 MediaStore 检测、权限适配、ContentObserver 和手动重试入口。
3. 实现原位照片读取、哈希去重、处理状态和 WorkManager 调度。
4. 实现设置表单、Keystore API Key 存储、OpenAI 兼容 VLM 客户端和严格 JSON v1 解析。
5. 实现 Prompt、客户端决策阈值、待确认列表、一次性结果提示和系统删除确认。
6. 实现官方账单导入、冲突处理、批次撤销和对账。
7. 实现 CSV/XLSX 月报、诊断包、Issue 模板和安全文档。
8. 在 API 26、33、最新稳定 API、AOSP/主流设备和 HyperOS 设备上完成端到端回归。

后续 AI 必须遵守：

- 不因单个关键词、自然语言或 VLM 自报 `decision` 直接自动入账；
- 不保存原始照片、完整 VLM/OCR 文本、Authorization Header 或 API Key；
- 只在用户启用云端识别并同意当前照片上传时联网；
- 不自行增加无障碍持续扫描、UsageStats、录屏、短信读取、Root、Shizuku 或静默删除能力；
- 修改识别规则或 Prompt 时必须增加正样本和负样本回归测试，优先防止误记账；
- 不把未验证的厂商差异写成通用 Android 保证，也不因单台设备表现扩大支持承诺。
