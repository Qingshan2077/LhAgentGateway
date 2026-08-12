# 一键压测脚本：启动 Mock 上游 -> 启动网关 -> 跑 JMeter -> 输出 QPS/P99 汇总
#
# 用法示例：
#   1) 代理转发场景（走完整链路到 Mock 上游）：
#      .\run-load-test.ps1 -Scenario proxy -Threads 200 -Duration 300
#   2) 缓存命中场景（固定请求体，命中本地缓存；需先实现缓存写路径）：
#      .\run-load-test.ps1 -Scenario cache -Threads 500 -Duration 300
#   3) 只跑 JMeter（网关已由 IDEA 启动，使用默认端口 8885）：
#      .\run-load-test.ps1 -SkipGatewayStart -SkipMockStart -Jmeter 'C:\apache-jmeter-5.6.3\bin\jmeter.bat'
#
# 常用参数：
#   -Scenario      proxy | cache，默认 proxy
#   -Threads       并发线程数，默认 200
#   -RampUp        启动时间(秒)，默认 30
#   -Duration      持续时长(秒)，默认 300
#   -GatewayHost   网关地址，默认 localhost
#   -GatewayPort   网关端口，默认 8885
#   -MockPort      Mock 上游端口，默认 9999
#   -MockDelayMs   Mock 上游模拟延迟(ms)，默认 5
#   -Jmeter        jmeter 可执行文件路径，默认 jmeter（需在 PATH）
#   -SkipGatewayStart / -SkipMockStart  跳过启动对应服务
#   -KeepRunning   测试结束后不自动关闭网关/Mock

param(
    [ValidateSet('proxy', 'cache')]
    [string]$Scenario = 'proxy',
    [int]$Threads = 200,
    [int]$RampUp = 30,
    [int]$Duration = 300,
    [string]$GatewayHost = 'localhost',
    [int]$GatewayPort = 8885,
    [int]$MockPort = 9999,
    [int]$MockDelayMs = 5,
    [string]$Jmeter = 'jmeter',
    [switch]$SkipGatewayStart,
    [switch]$SkipMockStart,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$loadTestDir = $PSScriptRoot
$mockSrc = Join-Path $loadTestDir 'MockLlmServer.java'
$mockOut = Join-Path $loadTestDir 'out'
$gatewayJar = Join-Path $repoRoot 'gateway-core\target\gateway-core-1.0.0-SNAPSHOT.jar'
$jmx = Join-Path $loadTestDir ("gateway-{0}-test.jmx" -f $Scenario)

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$resultDir = Join-Path $loadTestDir ("results\{0}-{1}" -f $Scenario, $stamp)
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
$jtl = Join-Path $resultDir 'result.jtl'
$reportDir = Join-Path $resultDir 'report'
$gwLog = Join-Path $resultDir 'gateway.log'
$mockLog = Join-Path $resultDir 'mock.log'

$procGateway = $null
$procMock = $null

function Test-Command([string]$name) {
    return $null -ne (Get-Command $name -ErrorAction SilentlyContinue)
}

function Wait-Health([string]$url, [int]$timeoutSec = 90) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri $url -TimeoutSec 3
            if ($r.status -eq 'UP') { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

try {
    # ---------- 0. 前置检查 ----------
    if (-not (Test-Command 'java')) { throw '未找到 java，请先安装 JDK 17 并加入 PATH。' }
    if (-not (Test-Command $Jmeter)) { throw "未找到 JMeter（$Jmeter），请先安装并指定 -Jmeter 参数。详见 README.md。" }
    if (-not (Test-Path $gatewayJar)) {
        throw "未找到网关 jar：$gatewayJar`n请在 IDEA 中先执行 Maven 构建（或 mvn package），再运行本脚本。"
    }
    if (-not (Test-Path $jmx)) { throw "未找到 JMeter 测试计划：$jmx" }

    # ---------- 1. 编译 Mock 上游 ----------
    if (-not (Test-Path (Join-Path $mockOut 'MockLlmServer.class'))) {
        Write-Host '[1/5] 编译 MockLlmServer ...'
        & javac -encoding UTF-8 -d $mockOut $mockSrc
        if ($LASTEXITCODE -ne 0) { throw 'MockLlmServer 编译失败。' }
    } else {
        Write-Host '[1/5] MockLlmServer 已编译'
    }

    # ---------- 2. 启动 Mock 上游 ----------
    if ($SkipMockStart) {
        Write-Host '[2/5] 跳过 Mock 启动（-SkipMockStart）'
    } else {
        Write-Host "[2/5] 启动 Mock 上游: localhost:$MockPort (delay=${MockDelayMs}ms)"
        $env:MOCK_PORT = "$MockPort"
        $env:MOCK_DELAY_MS = "$MockDelayMs"
        $procMock = Start-Process -FilePath 'java' -ArgumentList '-cp', $mockOut, 'MockLlmServer' `
            -WorkingDirectory $loadTestDir -RedirectStandardOutput $mockLog -RedirectStandardError $mockLog `
            -PassThru -WindowStyle Hidden
        Start-Sleep -Seconds 3
        if ($procMock.HasExited) { throw 'Mock 上游启动失败，请查看 mock.log。' }
    }

    # ---------- 3. 启动网关 ----------
    if ($SkipGatewayStart) {
        Write-Host '[3/5] 跳过网关启动（-SkipGatewayStart），使用已运行的实例'
    } else {
        Write-Host "[3/5] 启动网关: :$GatewayPort -> Mock(:$MockPort)"
        $gwArgs = @('-jar', $gatewayJar, "--server.port=$GatewayPort",
                  "--llm.upstream.uri=http://localhost:$MockPort")
        $procGateway = Start-Process -FilePath 'java' -ArgumentList $gwArgs `
            -WorkingDirectory (Split-Path -Parent $gatewayJar) `
            -RedirectStandardOutput $gwLog -RedirectStandardError $gwLog `
            -PassThru -WindowStyle Hidden
        $ok = Wait-Health "http://$GatewayHost`:$GatewayPort/actuator/health" 90
        if (-not $ok) {
            throw "网关 $GatewayPort 秒内未就绪，请查看 gateway.log。`n端口可能被占用：先停掉旧实例或改用 -GatewayPort 8899。"
        }
        Write-Host '    网关已就绪 (health=UP)'
    }

    # ---------- 4. 运行 JMeter ----------
    Write-Host "[4/5] 运行 JMeter: $Scenario 场景, 线程=$Threads, ramp=$RampUp, duration=${Duration}s"
    & $Jmeter -n -t $jmx `
        "-Jgw_host=$GatewayHost" "-Jgw_port=$GatewayPort" `
        "-Jthreads=$Threads" "-Jramp=$RampUp" "-Jduration=$Duration" `
        -l $jtl -e -o $reportDir
    if ($LASTEXITCODE -ne 0) { throw 'JMeter 执行失败。' }

    # ---------- 5. 汇总结果 ----------
    Write-Host '[5/5] 汇总结果 ...'
    if (-not (Test-Path $jtl)) { throw '未生成 result.jtl，无法汇总。' }

    $header = Get-Content $jtl -TotalCount 1
    $cols = $header.Split(',')
    $iTs = [Array]::IndexOf($cols, 'timeStamp')
    $iElapsed = [Array]::IndexOf($cols, 'elapsed')
    $iSuccess = [Array]::IndexOf($cols, 'success')
    if ($iTs -lt 0 -or $iElapsed -lt 0 -or $iSuccess -lt 0) {
        # 兼容不同版本 JMeter 的字段名
        $iTs = 0; $iElapsed = 1; $iSuccess = 7
    }

    $latencies = [System.Collections.Generic.List[long]]::new()
    $success = 0; $total = 0
    $minTs = [long]::MaxValue; $maxTs = [long]::MinValue
    Get-Content $jtl | Select-Object -Skip 1 | ForEach-Object {
        $f = $_.Split(',')
        if ($f.Count -le $iElapsed) { return }
        $total++
        $ts = 0; [long]::TryParse($f[$iTs], [ref]$ts) | Out-Null
        $el = 0; [long]::TryParse($f[$iElapsed], [ref]$el) | Out-Null
        $latencies.Add($el)
        if ($ts -gt 0) { if ($ts -lt $minTs) { $minTs = $ts }; if ($ts -gt $maxTs) { $maxTs = $ts } }
        if ($f[$iSuccess] -eq 'true') { $success++ }
    }

    if ($total -gt 0) {
        $latencies.Sort()
        $p = { param($arr, $pct) $idx = [Math]::Min($arr.Count - 1, [int][Math]::Ceiling($pct * $arr.Count) - 1); $arr[$idx] }
        $avg = [Math]::Round((($latencies | Measure-Object -Average).Average), 1)
        $p50 = & $p $latencies 0.50
        $p95 = & $p $latencies 0.95
        $p99 = & $p $latencies 0.99
        $spanSec = ($maxTs - $minTs) / 1000.0
        $qps = if ($spanSec -gt 0) { [Math]::Round($total / $spanSec, 1) } else { 0 }
        $errRate = [Math]::Round(100 * ($total - $success) / $total, 2)
        Write-Host ''
        Write-Host '==================== 压测结果 ===================='
        Write-Host ("  场景:       {0}  (线程={1}, ramp={2}s, duration={3}s)" -f $Scenario, $Threads, $RampUp, $Duration)
        Write-Host ("  总请求数:   {0}" -f $total)
        Write-Host ("  错误率:     {0}%" -f $errRate)
        Write-Host ("  QPS:        {0}  (按 JTL 时间戳计算)" -f $qps)
        Write-Host ("  平均延迟:   {0} ms" -f $avg)
        Write-Host ("  P50:        {0} ms" -f $p50)
        Write-Host ("  P95:        {0} ms" -f $p95)
        Write-Host ("  P99:        {0} ms" -f $p99)
        Write-Host '=================================================='
        Write-Host "  HTML 报告:   $reportDir\index.html"
        Write-Host "  JTL 原始数据: $jtl"
    } else {
        Write-Host 'JTL 中没有有效样本，请检查是否压测成功。'
    }
}
finally {
    if (-not $KeepRunning) {
        if ($procGateway -and -not $procGateway.HasExited) { Stop-Process -Id $procGateway.Id -Force }
        if ($procMock -and -not $procMock.HasExited) { Stop-Process -Id $procMock.Id -Force }
    }
}