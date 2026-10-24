<#
  兼容 Windows PowerShell 5.1 与 PowerShell 7+。本文件必须以 UTF-8 with BOM 保存，
  否则 Windows PowerShell 5.1 会按系统 ANSI 代码页读取，导致中文乱码并使解析失败。

.SYNOPSIS
  安装「通用研发团队」提示词包，使其在所有项目可用。

.DESCRIPTION
  两种模式：
    全局（默认）：把团队包装到 <agentsHome>/skills/agent-team/，并把精简总纲写入 $DSH_HOME/AGENTS.md
    项目：把团队包装到 <项目>/.agents/skills/agent-team/，并创建 docs/team/ 工件目录与骨架

  安全保证：
    - 绝不整体覆盖已有的 AGENTS.md，只替换 <!-- agent-team:begin --> ... <!-- agent-team:end --> 标记块
    - 安装前自动备份 AGENTS.md
    - 重复执行幂等
    - 只在 <agentsHome>/skills/agent-team 与项目内 docs/team 下写入

.EXAMPLE
  pwsh -File install.ps1 -Global
  pwsh -File install.ps1 -Global -FullOutline
  pwsh -File install.ps1 -Project D:\work\my-app
  pwsh -File install.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string]$Project,
    [switch]$Global,
    [switch]$FullOutline,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

$BEGIN = '<!-- agent-team:begin -->'
$END   = '<!-- agent-team:end -->'
$ROLE_FILES = @(
    '00-团队总纲.md', '09-升级矩阵与决策权.md', '10-工件规范.md',
    '01-主Agent.md', '02-产品经理.md', '03-架构师.md',
    '04-前端工程师.md', '05-后端工程师.md', '06-测试工程师.md', '07-运维工程师.md',
    '08-长期机制.md', '11-角色卡骨架.md', '12-UI设计师.md'
)
$TEMPLATE_FILES = @(
    'config.md', 'plan.md', 'status.md', 'decisions.md', '打回单.md',
    'PRD.md', 'CONTRACT.md', 'DESIGN.md', 'MR.md', 'TEST.md', 'BUG.md', 'REL.md'
)

function Get-Home {
    if ($env:USERPROFILE) { return $env:USERPROFILE }
    if ($HOME) { return $HOME }
    throw '无法解析用户主目录（USERPROFILE / HOME 均为空）'
}
function Get-AgentsHome {
    if ($env:DSH_AGENTS_HOME) { return $env:DSH_AGENTS_HOME }
    if ($env:AGENTS_HOME) { return $env:AGENTS_HOME }
    return (Join-Path (Get-Home) '.agents')
}
function Get-DshHome {
    if ($env:DSH_HOME) { return $env:DSH_HOME }
    return (Join-Path (Get-Home) '.dsh')
}

$PACK = $PSScriptRoot
$SRC  = Join-Path $PACK 'team'
if (-not (Test-Path (Join-Path $SRC '00-团队总纲.md'))) {
    throw "找不到团队包源目录：$SRC（install.ps1 必须与 team\ 目录放在一起）"
}

# ---------- 单文件版生成 ----------
function New-SingleFile {
    $out = Join-Path $SRC '全部提示词.md'
    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.AppendLine('# 通用研发团队 · 全部提示词（单文件版）')
    [void]$sb.AppendLine()
    [void]$sb.AppendLine("> 由 install.ps1 于 $(Get-Date -Format 'yyyy-MM-dd HH:mm') 自动生成，**请勿手工编辑**。")
    [void]$sb.AppendLine('> 用途：无法按需加载文件时（如网页版对话框）整体粘贴。')
    [void]$sb.AppendLine('> 能按需加载时不要用它 —— 它会一次性占用全部上下文。')
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('---')
    [void]$sb.AppendLine()
    foreach ($f in $ROLE_FILES) {
        $p = Join-Path $SRC $f
        if (-not (Test-Path $p)) { Write-Warning "跳过缺失文件：$f"; continue }
        [void]$sb.AppendLine("## 📄 $f")
        [void]$sb.AppendLine()
        [void]$sb.AppendLine((Get-Content -Raw -Encoding utf8 $p).TrimEnd())
        [void]$sb.AppendLine()
        [void]$sb.AppendLine('---')
        [void]$sb.AppendLine()
    }
    [void]$sb.AppendLine('# 附录：工件模板')
    [void]$sb.AppendLine()
    foreach ($f in $TEMPLATE_FILES) {
        $p = Join-Path $SRC "templates\$f"
        if (-not (Test-Path $p)) { Write-Warning "跳过缺失模板：$f"; continue }
        [void]$sb.AppendLine("## 📄 templates/$f")
        [void]$sb.AppendLine()
        [void]$sb.AppendLine((Get-Content -Raw -Encoding utf8 $p).TrimEnd())
        [void]$sb.AppendLine()
        [void]$sb.AppendLine('---')
        [void]$sb.AppendLine()
    }
    [System.IO.File]::WriteAllText($out, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))
    Write-Host "  已生成单文件版：$out" -ForegroundColor DarkGray
}

# ---------- 全局安装 ----------
function Install-Global {
    $agentsHome = Get-AgentsHome
    $dshHome    = Get-DshHome
    $skillsDir  = Join-Path $agentsHome 'skills'
    $dest       = Join-Path $skillsDir 'agent-team'

    if (-not ($dest -like "$skillsDir\*")) { throw "拒绝操作：目标路径异常 $dest" }

    Write-Host "[1/3] 安装 skill 包 -> $dest" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $skillsDir | Out-Null
    if (Test-Path $dest) { Remove-Item -Path $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -Path (Join-Path $SRC '*') -Destination $dest -Recurse -Force

    Write-Host "[2/3] 写入用户全局总纲 -> $(Join-Path $dshHome 'AGENTS.md')" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $dshHome | Out-Null
    $agentsFile = Join-Path $dshHome 'AGENTS.md'

    $existing = ''
    if (Test-Path $agentsFile) {
        $bak = "$agentsFile.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Copy-Item -Path $agentsFile -Destination $bak -Force
        Write-Host "  已备份原文件 -> $bak" -ForegroundColor DarkGray
        $existing = Get-Content -Raw -Encoding utf8 $agentsFile
    }

    # 只替换标记块，保留用户其余内容
    $existing = ($existing -replace "(?s)$BEGIN.*?$END", '').TrimEnd()

    if ($FullOutline) {
        $parts = foreach ($f in $ROLE_FILES) {
            $p = Join-Path $SRC $f
            if (Test-Path $p) { "### $f`n`n" + (Get-Content -Raw -Encoding utf8 $p).TrimEnd() }
        }
        $outline = ($parts -join "`n`n---`n`n")
        Write-Warning '-FullOutline 会把全部角色卡注入每个会话，会显著增加上下文开销。'
    }
    else {
        $outline = (Get-Content -Raw -Encoding utf8 (Join-Path $SRC '总纲-精简版.md')).TrimEnd()
    }

    $block = "$BEGIN`n$outline`n$END"
    $new = if ($existing) { "$existing`n`n$block`n" } else { "$block`n" }
    [System.IO.File]::WriteAllText($agentsFile, $new, [System.Text.UTF8Encoding]::new($false))

    Write-Host "[3/3] 校验" -ForegroundColor Cyan
    $ok1 = Test-Path (Join-Path $dest 'SKILL.md')
    $ok2 = (Select-String -Path $agentsFile -Pattern 'agent-team:begin' -Quiet) -eq $true
    Write-Host "  skill 入口: $(if($ok1){'OK'}else{'失败'})"
    Write-Host "  总纲注入块: $(if($ok2){'OK'}else{'失败'})"
    Write-Host ''
    Write-Host '安装完成。注意：新 skill 需要【新开会话】才会出现在技能目录中。' -ForegroundColor Green
    Write-Host '验证：新会话里执行 skill(agent-team)，或查看 / 菜单中的 agent-team。' -ForegroundColor Green
}

# ---------- 项目安装 ----------
function Install-Project([string]$projPath) {
    if (-not (Test-Path $projPath)) { throw "项目目录不存在：$projPath" }
    $projRoot   = (Resolve-Path $projPath).Path
    $skillsDir  = Join-Path $projRoot '.agents\skills'
    $dest       = Join-Path $skillsDir 'agent-team'
    $docsTeam   = Join-Path $projRoot 'docs\team'

    if (-not ($dest -like "$skillsDir\*")) { throw "拒绝操作：目标路径异常 $dest" }

    Write-Host "[1/3] 安装 skill 包 -> $dest" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $skillsDir | Out-Null
    if (Test-Path $dest) { Remove-Item -Path $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -Path (Join-Path $SRC '*') -Destination $dest -Recurse -Force

    Write-Host "[2/3] 创建工件目录 -> $docsTeam" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $docsTeam | Out-Null
    foreach ($d in 'prd', 'contracts', 'design', 'mr', 'tests', 'releases', 'reviews', 'memory') {
        New-Item -ItemType Directory -Force -Path (Join-Path $docsTeam $d) | Out-Null
    }

    Write-Host "[3/3] 放置骨架文件（不覆盖已有文件）" -ForegroundColor Cyan
    foreach ($t in 'config', 'plan', 'status', 'decisions') {
        $dst = Join-Path $docsTeam "$t.md"
        if (Test-Path $dst) { Write-Host "  跳过（已存在）：$t.md" -ForegroundColor DarkGray; continue }
        Copy-Item -Path (Join-Path $SRC "templates\$t.md") -Destination $dst -Force
        Write-Host "  已创建：docs/team/$t.md" -ForegroundColor DarkGray
    }
    $cl = Join-Path $docsTeam 'prompt-changelog.md'
    if (-not (Test-Path $cl)) {
        Set-Content -Path $cl -Encoding utf8 -Value @'
# 提示词迭代记录

> 每次因复盘而修改角色卡/规范时，在此追加一行。**改动必须有复盘依据。**

| 日期 | 改动文件 | 改动内容 | 依据（RETRO-nnn） |
| --- | --- | --- | --- |
| | | | |
'@
    }

    Write-Host ''
    Write-Host "项目安装完成：$projRoot" -ForegroundColor Green
    Write-Host '下一步：填写 docs/team/config.md（可写范围、命令、端口、红线），然后按 01-主Agent.md 的 G0 流程开工。' -ForegroundColor Green
}

# ---------- 卸载 ----------
function Uninstall-Pack {
    $agentsHome = Get-AgentsHome
    $skillsDir  = Join-Path $agentsHome 'skills'
    $dest       = Join-Path $skillsDir 'agent-team'
    if (-not ($dest -like "$skillsDir\*")) { throw "拒绝操作：目标路径异常 $dest" }
    if (Test-Path $dest) {
        Remove-Item -Path $dest -Recurse -Force
        Write-Host "已删除：$dest" -ForegroundColor Yellow
    }
    $agentsFile = Join-Path (Get-DshHome) 'AGENTS.md'
    if (Test-Path $agentsFile) {
        $c = Get-Content -Raw -Encoding utf8 $agentsFile
        $stripped = ($c -replace "(?s)$BEGIN.*?$END", '').TrimEnd()
        if ($stripped -ne $c.TrimEnd()) {
            Copy-Item $agentsFile "$agentsFile.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')" -Force
            [System.IO.File]::WriteAllText($agentsFile, "$stripped`n", [System.Text.UTF8Encoding]::new($false))
            Write-Host "已移除 AGENTS.md 中的 agent-team 标记块（其余内容保留）" -ForegroundColor Yellow
        }
    }
    Write-Host '卸载完成。项目内的 .agents/skills/agent-team 与 docs/team 未被删除，请按需手动清理。' -ForegroundColor Green
}

# ---------- 主流程 ----------
Write-Host ''
Write-Host '=== 通用研发团队 · 安装器 ===' -ForegroundColor Magenta
New-SingleFile
Write-Host ''

if ($Uninstall) { Uninstall-Pack; return }
if ($Project)   { Install-Project $Project; return }
Install-Global