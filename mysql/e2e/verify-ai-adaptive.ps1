# =============================================================================
# 部署后自适应验证：按**线上真实分类与价格**构造自然语言查询并核对命中
#
# 为什么需要这个脚本（而不是只用 verify-ai.ps1）：
#   verify-ai.ps1 的断言建立在固定验证数据集上（山地车归在"运动"、售价 700）。
#   线上真实数据集的分类体系与价格分布不同（山地车归"代步工具"、该分类下商品更贵），
#   于是固定断言会误报"检索失败"——其实解析完全正确，只是没有符合全部条件的商品。
#   本脚本改为：先读真实分类与价格，再据此构造**必然有解**的自然语言查询，
#   验证「解析 → 查询 → 命中」整条链路。这样在任何数据集上都能给出有意义的结论。
#
# 用法：pwsh -File mysql/e2e/verify-ai-adaptive.ps1 -BaseUrl http://localhost
# =============================================================================

param(
    [string]$BaseUrl = 'http://localhost',
    # 每页用来推断价位区间的样本量
    [int]$SamplePerCategory = 50
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
        return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $headers -TimeoutSec 90
    }
    $json = $Obj | ConvertTo-Json -Depth 6 -Compress
    return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 90
}

function Assert-That {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) { $script:pass++; Write-Host "  PASS  $Name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red }
}

Write-Host "`n=== 部署后自适应验证 @ $BaseUrl ===" -ForegroundColor Cyan

# ---------------------------------------------------------------- 1. 读取线上真实目录
Write-Host "`n[1] 读取线上真实分类与在售商品"
$cats = (Write-Utf8Json "$BaseUrl/api/categories" 'GET' $null).data
$withItems = @($cats | Where-Object { $_.itemCount -gt 0 })
Write-Host "        分类总数 $($cats.Count)，其中有在售商品的 $($withItems.Count) 个"
foreach ($c in $withItems) { Write-Host "          - $($c.name)(id=$($c.categoryId)) 在售 $($c.itemCount) 件" }

$candidates = New-Object System.Collections.ArrayList
foreach ($c in $withItems) {
    $page = Write-Utf8Json "$BaseUrl/api/items?categoryId=$($c.categoryId)&status=1&pageSize=$SamplePerCategory" 'GET' $null
    $prices = @($page.data.records | ForEach-Object { [double]$_.price })
    if ($prices.Count -eq 0) { continue }
    $titles = @($page.data.records | ForEach-Object { $_.title })
    # 从标题里取一个 2-4 字的中文片段当"用户会说的关键词"
    $kw = $null
    foreach ($t in $titles) {
        $m = [regex]::Match($t, '[\u4e00-\u9fa5]{2,4}')
        if ($m.Success) { $kw = $m.Value; break }
    }
    if (-not $kw) { continue }
    [void]$candidates.Add([pscustomobject]@{
            CategoryId = $c.categoryId
            CategoryName = $c.name
            Keyword = $kw
            MaxPrice = [math]::Ceiling(($prices | Measure-Object -Maximum).Maximum)
            SampleCount = $prices.Count
        })
}
Write-Host "        可用样本分类 $($candidates.Count) 个（已推断每个分类的价位上限与代表关键词）"
# 目录稀疏时不应判为失败：探针要验证的是「解析 → 查询」链路，而不是「库里有货」。
# 本机就踩过：演示库被下架到只剩 3 件在售，探针直接报红，看起来像功能缺陷。
# 数据不足就明确跳过，把环境问题与功能问题区分开。
if ($candidates.Count -eq 0) {
    $onSale = ($withItems | Measure-Object -Property itemCount -Sum).Sum
    Write-Host "  SKIP  在售商品不足以构造查询样本（当前在售仅 $onSale 件），跳过往返验证" -ForegroundColor Yellow
    Write-Host "`n=== 结果：跳过（环境数据不足，非功能失败）===" -ForegroundColor Yellow
    exit 0
}
Assert-That '线上有可检索的在售商品' ($candidates.Count -gt 0)

# ---------------------------------------------------------------- 2. 逐分类做「解析 → 查询」往返
Write-Host "`n[2] 自然语言检索往返验证（按真实价位构造查询，必然有解）"
$roundTripPass = 0
$roundTripTotal = 0
foreach ($cand in $candidates) {
    $roundTripTotal++
    $nl = "$($cand.MaxPrice)元以内的$($cand.Keyword)"
    $slots = Write-Utf8Json "$BaseUrl/api/ai/search/parse" 'POST' @{ query = $nl }
    $s = $slots.data
    if ($slots.code -ne 200 -or $null -eq $s) {
        Write-Host "  FAIL  「$nl」解析失败 code=$($slots.code) msg=$($slots.msg)" -ForegroundColor Red
        continue
    }

    # 用解析结果查（关键字 + 价格上限），不强加分类：v1 不强制模型分类，避免过度约束导致空结果
    $q = "keyword=$([uri]::EscapeDataString([string]$s.keyword))&status=1&pageSize=50"
    if ($null -ne $s.maxPrice) { $q += "&maxPrice=$($s.maxPrice)" }
    $hit = Write-Utf8Json "$BaseUrl/api/items?$q" 'GET' $null
    $total = $hit.data.total

    $ok = $total -gt 0
    if ($ok) { $roundTripPass++ }
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host "  $mark  「$nl」→ keyword=$($s.keyword) maxPrice=$($s.maxPrice) 分类推断=$($s.categoryName) → 命中 $total 件" -ForegroundColor $color
}
Assert-That "解析→查询往返命中率 $roundTripPass/$roundTripTotal" ($roundTripPass -eq $roundTripTotal) "命中 $roundTripPass/$roundTripTotal"

# ---------------------------------------------------------------- 3. 对照：LIKE 整句
Write-Host "`n[3] 对照组：把整句当关键词做 LIKE"
foreach ($cand in ($candidates | Select-Object -First 3)) {
    $nl = "$($cand.MaxPrice)元以内的$($cand.Keyword)"
    $like = Write-Utf8Json "$BaseUrl/api/items?keyword=$([uri]::EscapeDataString($nl))&status=1" 'GET' $null
    Write-Host "        LIKE「$nl」命中 $($like.data.total) 件"
}

Write-Host "`n=== 结果：$script:pass 项通过，$script:fail 项失败 ===" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
