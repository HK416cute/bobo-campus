# =============================================================================
# 发布限额验证：同一用户最多同时挂 5 件待审核商品（第 6 件被拒）
#
# 为什么要单独一个脚本：这条规则改过两次需求（1 → 5），而且"限额"容易被误解成
# "历史总发布量"。用接口真发 6 次能一次性证明三件事：
#   ① 前 5 次成功、第 6 次被拒（上限确实是 5）；
#   ② 错误信息里带上了当前数量与上限（前端能直接展示，不需要自己拼文案）；
#   ③ overview 的 pendingCount/pendingLimit 与实际一致（前端提示不会说谎）。
# 跑完会把测试账号与其商品清掉（可加 -Keep 保留用于人工看页面）。
#
# 用法：pwsh -File mysql/e2e/verify-publish-limit.ps1 -BaseUrl http://localhost/bobo-campus
# =============================================================================

param(
    [string]$BaseUrl = 'http://localhost/bobo-campus',
    [switch]$Keep
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
        return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $headers -TimeoutSec 60
    }
    $json = $Obj | ConvertTo-Json -Depth 6 -Compress
    return Invoke-RestMethod -Uri $Uri -Method $Method -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 60
}

function Assert-That {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) { $script:pass++; Write-Host "  PASS  $Name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red }
}

Write-Host "`n=== 发布限额验证 @ $BaseUrl ===" -ForegroundColor Cyan

# ---------------------------------------------------------------- 准备账号
$username = "limit_$(Get-Random -Maximum 999999)"
$reg = Write-Utf8Json "$BaseUrl/api/auth/register" 'POST' @{
    username = $username; password = 'bobo123456'; nickname = '限额验证'
}
if ($reg.code -ne 200) { Write-Host "注册失败：$($reg.msg)" -ForegroundColor Red; exit 1 }
$token = $reg.data.token
Write-Host "  已注册验证账号 $username"

# ---------------------------------------------------------------- 读上限
$ov = Write-Utf8Json "$BaseUrl/api/auth/overview" 'GET' $null -Token $token
$limit = [int]$ov.data.pendingLimit
$pending0 = [int]$ov.data.pendingCount
Write-Host "  后端下发：pendingLimit=$limit pendingCount=$pending0"
Assert-That '上限为 5' ($limit -eq 5) "got=$limit"
Assert-That '新账号待审核数为 0' ($pending0 -eq 0) "got=$pending0"

# ---------------------------------------------------------------- 连发 6 件
$successCount = 0
$rejectMessage = ''
for ($i = 1; $i -le 6; $i++) {
    $body = @{
        title       = "限额验证商品 $i"
        description = '自动化验证用，稍后清理'
        price       = 10.00
        categoryId  = 1
        conditionLevel = '95新'
        tradeType   = 0
        campus      = '东校区'
        images      = @()
    }
    $r = Write-Utf8Json "$BaseUrl/api/items" 'POST' $body -Token $token
    if ($r.code -eq 200) {
        $successCount++
        Write-Host "  第 $i 件：发布成功 (itemId=$($r.data.itemId))"
    } else {
        $rejectMessage = $r.msg
        Write-Host "  第 $i 件：被拒 → $($r.msg)" -ForegroundColor Yellow
    }
}
Assert-That '前 5 件全部发布成功' ($successCount -eq 5) "成功 $successCount 件"
Assert-That '第 6 件被拒' ([bool]$rejectMessage)
# 断言到具体措辞而不是"含 5"：后者可能被"第 5 件"之类的偶然数字满足，属于假阳性
Assert-That '拒绝文案含"最多同时提交 5 件"' ($rejectMessage -match '最多同时提交\s*5\s*件') "got=$rejectMessage"
Assert-That '拒绝文案含"审核中"' ($rejectMessage -match '审核中') "got=$rejectMessage"

# ---------------------------------------------------------------- overview 与实况一致
$ov2 = Write-Utf8Json "$BaseUrl/api/auth/overview" 'GET' $null -Token $token
Assert-That 'overview.pendingCount 与实况一致（=5）' ([int]$ov2.data.pendingCount -eq 5) "got=$($ov2.data.pendingCount)"

# ---------------------------------------------------------------- 释放名额
# 模拟"审核出结果即释放"：把一件置为已下架（等价于不再占用待审核名额）。
# 这一步需要改库，因此先确认 docker 可用；不可用时跳过而不是误报失败。
$dockerOk = $false
try {
    docker version --format '{{.Server.Version}}' 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $dockerOk = $true }
} catch { $dockerOk = $false }

if (-not $dockerOk) {
    Write-Host "  SKIP  未检测到可用 Docker，跳过「名额释放」验证（需改库模拟审核结果）" -ForegroundColor Yellow
    Write-Host "`n=== 结果：$script:pass 项通过，$script:fail 项失败（部分跳过）===" -ForegroundColor Green
    exit 0
}

$rp = docker exec bobo-campus-mysql sh -c 'printenv MYSQL_ROOT_PASSWORD'
docker exec bobo-campus-mysql mysql -uroot -p"$rp" -D bobo -e `
    "UPDATE bobo_item SET status=3 WHERE seller_id=(SELECT user_id FROM bobo_user WHERE username='$username') LIMIT 1;" 2>&1 | Out-Null
$r7 = Write-Utf8Json "$BaseUrl/api/items" 'POST' @{
    title = '限额释放后新发'; description = 'x'; price = 10.00; categoryId = 1; images = @()
} -Token $token
Write-Host "  释放一个名额后再发：code=$($r7.code) msg=$($r7.msg)"
Assert-That '名额释放后可再发布' ($r7.code -eq 200) "code=$($r7.code)"

# ---------------------------------------------------------------- 清理
if (-not $Keep) {
    $uid = (Write-Utf8Json "$BaseUrl/api/auth/me" 'GET' $null -Token $token).data.userId
    docker exec bobo-campus-mysql mysql -uroot -p"$rp" -D bobo -e `
        "DELETE FROM bobo_item WHERE seller_id=$uid; DELETE FROM bobo_user WHERE user_id=$uid;" 2>&1 | Out-Null
    Write-Host "  已清理测试账号与商品"
} else {
    Write-Host "  -Keep：保留测试数据用于人工查看" -ForegroundColor Yellow
}

Write-Host "`n=== 结果：$script:pass 项通过，$script:fail 项失败 ===" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
