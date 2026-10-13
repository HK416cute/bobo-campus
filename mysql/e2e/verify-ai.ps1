# =============================================================================
# AI 能力端到端验证探针（人工验证用，不参与构建）
#
# 为什么单独写脚本而不是随手敲几条命令：
#   1. PowerShell 的 Invoke-WebRequest 对中文请求体默认按非 UTF-8 编码发送，
#      会出现「中文 query 变问号 → 后端解析出乱码」的假故障，浪费排查时间；
#   2. 端到端验证要覆盖「真实模型」与「降级模式」两种形态、以及采纳反馈闭环，
#      逐条手敲容易漏项，也不可复现。
#
# 用法（需先起好 MySQL 与应用）：
#   pwsh -File mysql/e2e/verify-ai.ps1 -BaseUrl http://127.0.0.1:19099
# =============================================================================

param(
    [string]$BaseUrl = 'http://127.0.0.1:19099',
    [string]$ApiKey = $env:BOBO_AI_API_KEY,
    # 管理端 AI 接口需要管理员登录态。传自带的管理员 token 即可覆盖第 5 节：
    #   UPDATE bobo_user SET role='admin' WHERE user_id=<你的验证账号>;
    #   （然后用该账号登录拿 token）
    [string]$AdminToken = $env:BOBO_AI_ADMIN_TOKEN
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$script:pass = 0
$script:fail = 0

function Write-Utf8Json {
    param($Uri, $Method, $Obj, [string]$Token = '')
    $headers = @{ 'Accept' = 'application/json' }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    if ($null -eq $Obj) {
        # 注意：GET 不能带 -Body（哪怕 $null），否则后端报「请求体格式错误」
        return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $headers -TimeoutSec 90
    }
    $json = $Obj | ConvertTo-Json -Depth 6 -Compress
    # PowerShell 7 下把字符串 body 与 charset=utf-8 组合即可正确编码中文
    return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 90
}

function Get-LoginToken {
    param([string]$Base)
    # 用注册接口现建一个账号拿登录态：既验证了注册链路，也不依赖 02-seed 的密码散列
    $username = "ai_e2e_$(Get-Random -Maximum 999999)"
    $payload = @{ username = $username; password = 'bobo123456'; nickname = 'AI验证账号' }
    $reg = Write-Utf8Json "$Base/api/auth/register" 'POST' $payload
    if ($reg.code -eq 200) {
        Write-Host "        已注册验证账号 $username"
        return $reg.data.token
    }
    # 注册失败（例如用户名规则变化）时退回登录已有账号
    $login = Write-Utf8Json "$Base/api/auth/login" 'POST' @{ username = $username; password = 'bobo123456' }
    if ($login.code -eq 200) { return $login.data.token }
    Write-Host "        无法取得登录态：register=$($reg.msg) login=$($login.msg)" -ForegroundColor Yellow
    return $null
}

function Assert-That {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:pass++
        Write-Host "  PASS  $Name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red
    }
}

Write-Host "`n=== AI 能力端到端验证 @ $BaseUrl ===" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 登录态
Write-Host "`n[0] 取得登录态（发布助手与反馈接口需要登录）"
$token = Get-LoginToken -Base $BaseUrl
Assert-That '拿到可用登录态' ([bool]$token)

# ---------------------------------------------------------------- 1. 能力状态
Write-Host "`n[1] AI 能力状态"
$status = Write-Utf8Json "$BaseUrl/api/ai/status" 'GET' $null
Assert-That '接口返回 200' ($status.code -eq 200) "code=$($status.code)"
Assert-That '返回 available/degraded 字段' ($null -ne $status.data.available)
Write-Host "        available=$($status.data.available) model=$($status.data.model) reason=$($status.data.reason)"

# ---------------------------------------------------------------- 2. 发布助手
Write-Host "`n[2] AI 发布助手（真实模型或规则降级）"
$draft = Write-Utf8Json "$BaseUrl/api/ai/publish/draft" 'POST' @{
    text = '九成新 iPad Air4 64G 深空灰，宿舍自用含原装充电器，想卖1800，东校区当面交易，可小刀'
} -Token $token
Assert-That '返回 200' ($draft.code -eq 200) "code=$($draft.code) msg=$($draft.msg)"
$d = $draft.data
Assert-That '标题非空' ([bool]$d.title)
Assert-That '成色是库内合法值' (@('全新','99新','95新','9成新','8成新','7成新及以下') -contains $d.conditionLevel) "got=$($d.conditionLevel)"
Assert-That 'source 已标明来源' (@('ai','rule') -contains $d.source) "got=$($d.source)"
Write-Host "        source=$($d.source) latency=$($d.latencyMs)ms"
Write-Host "        标题=$($d.title)"
Write-Host "        分类=$($d.categoryName)(id=$($d.categoryId)) 成色=$($d.conditionLevel)"
Write-Host "        标签=$($d.tags -join ',')"
Write-Host "        描述=$($d.description)"
Write-Host "        风险标记=$($d.riskFlags -join ',')"
if ($d.priceSuggestion) {
    Write-Host "        价格建议 low=$($d.priceSuggestion.low) mid=$($d.priceSuggestion.mid) high=$($d.priceSuggestion.high) 样本=$($d.priceSuggestion.sampleCount)"
    Assert-That '价格建议带样本量与依据' ([bool]$d.priceSuggestion.basis -and $d.priceSuggestion.sampleCount -gt 0)
} else {
    Write-Host "        价格建议：无（样本不足或未选分类）"
}

# ---------------------------------------------------------------- 3. 自然语言检索
Write-Host "`n[3] 自然语言检索意图解析"

# 3.1 解析正确性：预算 500 时应当解析出价格上限与交易方式
$query = '500以内能当面交易的山地车'
$slots = Write-Utf8Json "$BaseUrl/api/ai/search/parse" 'POST' @{ query = $query }
Assert-That '返回 200' ($slots.code -eq 200) "code=$($slots.code) msg=$($slots.msg)"
$s = $slots.data
Assert-That '解析出关键词' ([bool]$s.keyword) "keyword=$($s.keyword)"
Assert-That '识别出价格上限' ($null -ne $s.maxPrice) "maxPrice=$($s.maxPrice)"
Assert-That '识别出交易方式=仅当面' ($s.tradeType -eq 1) "tradeType=$($s.tradeType)"
Write-Host "        keyword=$($s.keyword) 分类=$($s.categoryName)(id=$($s.categoryId)) 价格=[$($s.minPrice),$($s.maxPrice)] 交易=$($s.tradeType) source=$($s.source) 耗时=$($s.latencyMs)ms"
Write-Host "        explanation=$($s.explanation)"

# 3.2 对照组：整句话当关键词做 LIKE，几乎必然查不到（这正是做意图解析的理由）
$like = Write-Utf8Json "$BaseUrl/api/items?keyword=$([uri]::EscapeDataString($query))&status=1" 'GET' $null
Write-Host "        对照 LIKE 整句命中 $($like.data.total) 件"

# 3.3 端到端：换成预算够得着的问法，验证「解析 → 查询」真能筛出商品
$query2 = '800以内能当面交易的山地车'
$slots2 = Write-Utf8Json "$BaseUrl/api/ai/search/parse" 'POST' @{ query = $query2 }
$s2 = $slots2.data
Write-Host "        第二条：'$query2' → keyword=$($s2.keyword) 分类=$($s2.categoryName) 价格=[$($s2.minPrice),$($s2.maxPrice)] 交易=$($s2.tradeType)"
$q = "keyword=$([uri]::EscapeDataString([string]$s2.keyword))&status=1&pageSize=20"
if ($null -ne $s2.maxPrice) { $q += "&maxPrice=$($s2.maxPrice)" }
if ($null -ne $s2.tradeType) { $q += "&tradeType=$($s2.tradeType)" }
if ($null -ne $s2.categoryId) { $q += "&categoryId=$($s2.categoryId)" }
$hit = Write-Utf8Json "$BaseUrl/api/items?$q" 'GET' $null
Assert-That '按解析槽位能查到商品' ($hit.data.total -gt 0) "total=$($hit.data.total)"
Write-Host "        结构化查询命中 $($hit.data.total) 件：$(($hit.data.records | ForEach-Object { $_.title }) -join ' / ')"

# 3.4 模型过度约束的可见性：不能悄悄把结果筛空
#     验证数据集里山地车售价 700，因此预算 500 的正确行为就是 0 条
$over = Write-Utf8Json "$BaseUrl/api/items?keyword=$([uri]::EscapeDataString([string]$s.keyword))&maxPrice=$($s.maxPrice)&tradeType=$($s.tradeType)&categoryId=$($s.categoryId)&status=1" 'GET' $null
Assert-That '预算不足时结果为空是正确行为（解析未失真）' ($over.data.total -eq 0) "total=$($over.data.total)"

# ---------------------------------------------------------------- 4. 采纳反馈
Write-Host "`n[4] 采纳反馈闭环"
$fb = Write-Utf8Json "$BaseUrl/api/ai/feedback" 'POST' @{
    suggestionType = 'publish_draft'
    accepted = $true
    suggestedValue = $d.title
    finalValue = $d.title
    logId = $d.logId
} -Token $token
Assert-That '反馈写入返回 200' ($fb.code -eq 200) "code=$($fb.code) msg=$($fb.msg)"
$bad = Write-Utf8Json "$BaseUrl/api/ai/feedback" 'POST' @{ suggestionType = 'not_a_type'; accepted = $true } -Token $token
Assert-That '未知建议类型被拒绝（统计口径不被污染）' ($bad.code -eq 400) "code=$($bad.code)"

# ---------------------------------------------------------------- 5. 管理端 AI
if ($AdminToken) {
    Write-Host "`n[5] 管理端 AI（效果面板 / 审核 / 运营 Copilot）"

    $m = Write-Utf8Json "$BaseUrl/api/admin/ai/metrics" 'GET' $null -Token $AdminToken
    Assert-That '效果面板返回 200' ($m.code -eq 200) "code=$($m.code) msg=$($m.msg)"
    Assert-That '面板含采纳率与分位耗时' ($null -ne $m.data.publishDraftAcceptRate -and $null -ne $m.data.p95LatencyMs)
    Write-Host "        累计调用=$($m.data.totalCalls) 成功率=$($m.data.successRate) 降级率=$($m.data.degradeRate) 成本=¥$($m.data.estimatedCost) tokens=$($m.data.totalTokens)"
    Write-Host "        P50/P95/P99=$($m.data.p50LatencyMs)/$($m.data.p95LatencyMs)/$($m.data.p99LatencyMs)ms"
    Write-Host "        发布建议采纳率=$($m.data.publishDraftAcceptRate)（$($m.data.publishDraftAccepted)/$($m.data.publishDraftTotal)） 检索改写采纳率=$($m.data.searchQueryAcceptRate)（$($m.data.searchQueryAccepted)/$($m.data.searchQueryTotal)）"

    $ai = Write-Utf8Json "$BaseUrl/api/admin/ai/report?days=7" 'GET' $null -Token $AdminToken
    Assert-That '运营周报返回 200' ($ai.code -eq 200) "code=$($ai.code) msg=$($ai.msg)"
    Assert-That '周报非空' ([bool]$ai.data.report)
    Assert-That '周报附带事实快照（可核对，防编造）' ($null -ne $ai.data.facts)
    Write-Host "        source=$($ai.data.source) 耗时=$($ai.data.latencyMs)ms"
    Write-Host "        周报内容（前 300 字）："
    Write-Host ("        " + ([string]$ai.data.report).Substring(0, [Math]::Min(300, ([string]$ai.data.report).Length)).Replace("`n", "`n        "))

    $mod = Write-Utf8Json "$BaseUrl/api/admin/ai/moderate" 'POST' @{
        targetType = 'item'
        content = '九成新 iPad Air4 64G，宿舍自用含原装充电器，东校区当面交易'
    } -Token $AdminToken
    Assert-That '内容预判返回 200' ($mod.code -eq 200) "code=$($mod.code) msg=$($mod.msg)"
    Write-Host "        正常文案 → 风险=$($mod.data.riskLevel) 类目=$($mod.data.riskCategories -join ',') 来源=$($mod.data.source)"

    $modBad = Write-Utf8Json "$BaseUrl/api/admin/ai/moderate" 'POST' @{
        targetType = 'item'
        content = '转让管制刀具一把，价格好商量'
    } -Token $AdminToken
    Write-Host "        违规文案 → 风险=$($modBad.data.riskLevel) 类目=$($modBad.data.riskCategories -join ',') 来源=$($modBad.data.source) 建议拦截=$($modBad.data.autoReject)"

    $list = Write-Utf8Json "$BaseUrl/api/admin/ai/moderation" 'GET' $null -Token $AdminToken
    Assert-That '审核结论列表返回 200' ($list.code -eq 200) "code=$($list.code) msg=$($list.msg)"
    Write-Host "        审核记录 $($list.data.Count) 条"

    $hr = Write-Utf8Json "$BaseUrl/api/admin/ai/moderation/high-risk-count" 'GET' $null -Token $AdminToken
    Assert-That '高风险角标返回 200' ($hr.code -eq 200) "code=$($hr.code) msg=$($hr.msg)"
    Write-Host "        高风险数量=$($hr.data)"
} else {
    Write-Host "`n[i] 未提供 -AdminToken：跳过管理端 AI 接口验证" -ForegroundColor Yellow
}

Write-Host "`n=== 结果：$script:pass 项通过，$script:fail 项失败 ===" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
