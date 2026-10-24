# 适配层 · DeepSeek Harness（DSH）

> 本文件记录 DSH 的实际装载机制，用于把本团队包安装到**所有项目都可用**。
> 事实来源：DSH 应用内文档（skill 发现根与指令链语义）。

## 一、DSH 的 skill 发现根（按优先级）

| 优先级 | 名称 | 路径 | 作用域 |
| --- | --- | --- | --- |
| 100 | `project-dsh` | `<项目根>/.dsh/skills` | 本项目 |
| 200 | `project-agents` | `<项目根>/.agents/skills` | 本项目（与 Claude Code 等共享约定） |
| 400 | `user-dsh` | `$DSH_HOME/skills`（默认 `~/.dsh/skills`） | **所有项目** |
| 500 | `user-agents` | `$DSH_AGENTS_HOME/skills`（默认 `~/.agents/skills`） | **所有项目**（跨工具共享） |

**发现规则（必须遵守）**：
- 一个 skill 要么是**目录包** `<name>/SKILL.md`（必须位于扫描根**顶层**），要么是**平铺文件** `<name>.md`。
- **刻意不支持**发现嵌套的 `**/SKILL.md`。所以子目录里的 .md 只是资源文件，不会被当成 skill。
- 目录名应与 frontmatter 的 `name` 一致。

**frontmatter 字段**：`name`（必填）、`description`（必填）、`whenToUse`、`metadata`、`disable-model-invocation`、`user-invocable`。

## 二、DSH 的指令注入链（决定「总纲」放哪）

- 每个会话的**第一次请求**包含一条持久基线消息：
  1. 用户全局 `$DSH_HOME/AGENTS.md`
  2. 然后是项目指令链：从项目根目录到会话工作目录，逐级收集现有候选文件
- 同级内容相同的文件只渲染一次（复制来的 `CLAUDE.md` 不会重复加载）。
- 项目 scope 还会加载 `AGENTS.local.md` / `CLAUDE.local.md` 作为 overlay；**用户全局 scope 没有本地 overlay**。

**结论**：想把「团队总纲」注入**每一个项目的每一个会话**，唯一正确位置是 `$DSH_HOME/AGENTS.md`。

## 三、两种安装模式

### 模式 A：全局安装（推荐，真正「所有项目可用」）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 -Global
```

> `-ExecutionPolicy Bypass` 是必需的：受限会话下未签名的本地脚本会被拒绝加载。
> 脚本本身兼容 Windows PowerShell 5.1 与 PowerShell 7+，文件以 UTF-8 with BOM 保存（5.1 依赖 BOM 才能正确读取中文）。

做三件事：
1. 把团队包复制到 `~/.agents/skills/agent-team/`（优先级 500，跨工具共享；若设置了 `DSH_AGENTS_HOME` 则用该值）。
2. 把**精简版总纲**以标记块形式写入 `$DSH_HOME/AGENTS.md`，用 `<!-- agent-team:begin -->` / `<!-- agent-team:end -->` 包裹。
3. 安装前备份原 `AGENTS.md` 为 `AGENTS.md.bak-<时间戳>`。

**安全保证**：**只做标记块替换，绝不整体覆盖用户已有的 `AGENTS.md`**；重复安装是幂等的。

### 模式 B：项目级安装

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 -Project D:\path\to\project
```

复制到 `<项目根>/.agents/skills/agent-team/`，并创建 `<项目根>/docs/team/` 与状态文档骨架（不会碰 `$DSH_HOME/AGENTS.md`）。

**何时选 B**：项目想自定角色/流程，或团队不想在全局注入内容。

## 四、为什么全局只注入「精简版总纲」

全局 `AGENTS.md` 会进入**每个会话**的每一次请求基线，全量团队文档（十几份角色卡）会白吃大量上下文。

因此全局注入的是一份**约 30 行的精简总纲**：团队构成 + 铁律 + 何时起团队 + 指向 skill 的指针。完整角色卡留在 skill 包里，**按需加载**。

若确实需要全量注入：安装时加 `-FullOutline`。

## 五、验证安装是否生效

```powershell
# 1. 文件是否就位
Test-Path "$env:USERPROFILE\.agents\skills\agent-team\SKILL.md"
Test-Path "$env:DSH_HOME\AGENTS.md"

# 2. 标记块是否存在
Select-String -Path "$env:DSH_HOME\AGENTS.md" -Pattern 'agent-team:begin'

# 3. 会话内验证：新开一个会话，技能目录里应出现 agent-team
```

**注意**：新装的 skill 需要**新会话**（或重新扫描 skill 目录）才会出现在技能目录里；已运行的会话不会自动感知。

## 六、注入内容改动的传播

- 改 `$DSH_HOME/AGENTS.md` → **下一个请求**即生效（指令文件会被重新读取）。
- 改 skill 正文 → 每次加载都会重新读当前文件，**无需版本化或缓存失效**。
- 改角色卡 → 同一 skill 目录内，重新加载即生效。

## 七、与 Claude Code 等其他工具的共享

`~/.agents/skills` 是跨工具共享约定（本机已有 `blueprint`、`grilling`、`springboot-patterns` 等）。把本包装到这里，Claude Code 等同样能发现 `agent-team` skill。

若要各自独立，改用 `$DSH_HOME/skills`（优先级 400，仅 DSH 可见）。

## 八、其他平台的等价做法

| 平台 | 做法 |
| --- | --- |
| Claude Code | 同上，`~/.agents/skills/agent-team/` 或 `~/.claude/skills/agent-team/`；总纲写入 `~/.claude/CLAUDE.md` |
| Cursor | 总纲写入 `.cursorrules` 或项目 `AGENTS.md`；角色卡按需 @ 引用 |
| 网页版对话框 | 用 `全部提示词.md` 单文件版一次性粘贴 |
| 自建 Agent | 总纲注入 system prompt；角色卡在切换角色时替换注入 |