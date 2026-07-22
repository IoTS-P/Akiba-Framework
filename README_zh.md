**注意：请不要单独 clone 这个仓库，请使用下面的命令克隆整个 Akiba 项目：**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba 运行框架

Akiba 运行框架是 Akiba 工具的运行入口。

## 如何运行

### Ghidra 预处理

1. 在这里下载 Ghidra 12.1：[链接](https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.1_build/ghidra_12.1_PUBLIC_20260513.zip)
2. 解压 Ghidra.zip
3. (可选但一些模块可能需要) 解压一些 Ghidra 官方扩展 (zip 文件) 到 `/Ghidra/Features`，这样 Ghidra 在 Jar 打包时可以将这些扩展打包进 `ghidra.jar` （默认打包 `MachineLearning` 扩展）
4. 到 `/support` 目录运行 `buildGhidraJar` 脚本文件（在 Windows 上运行`buildGhidraJar.bat`）
5. 如果一切顺利，你可以在 `/support` 找到 `ghidra.jar` 文件，这就是 Akiba 需要的依赖之一，将其复制到 `/path/to/Akiba/lib`

注意：Akiba 目前仅在 x64 架构中测试运行成功，如果你需要在非 x64 架构处理器上运行 Ghidra 及 Akiba，你需要首先转到 Ghidra 根目录下的 support/gradle 子目录，运行下面的命令：
```shell
gradle buildNatives
```
在 MacOS X Apple Silicon 系统上，直接运行上述命令可能无法成功，可尝试下面的方法（仅供参考）： 

#### 缺少 binutils-2.41.tar.bz2

GnuDisassembler 扩展需要 GNU binutils 的源代码来编译 gdis。这个文件不包含在 Ghidra 发行包中，需要手动下载。

解决方案：从 https://ftp.gnu.org/pub/gnu/binutils/binutils-2.41.tar.bz2 下载并放到 Ghidra/Extensions/GnuDisassembler/
目录下。

#### zlib 中 fdopen 宏与 macOS SDK 冲突

binutils 2.41 自带的 zlib 中，`zutil.h` 在检测到 `TARGET_OS_MAC` 时会定义 `#define fdopen(fd,mode) NULL`，这与新版 macOS SDK
(Xcode/CommandLineTools) 中 `_stdio.h` 的 `fdopen` 函数声明冲突。

解决方案：修改 `build/binutils-2.41/zlib/zutil.h`，在该宏定义处增加 `!defined(__APPLE__)` 条件判断，避免在现代 Apple
编译器下定义这个有问题的宏。

#### 缺少 makeinfo 工具

binutils 编译文档时需要 makeinfo（texinfo 包），macOS 默认未安装。

解决方案：通过 `brew install texinfo` 安装。

### 编译

使用一行命令编译 Akiba 数据库守护程序、Akiba 框架和 Akiba 模块

```shell
# 在 Akiba 主目录下运行
./gradelw assemble  # Windows 系统使用 gradlew.bat
```

### 运行

```shell
cd build/distributions
unzip Akiba-<版本号>.zip
cd Akiba-<版本号>

# 将需要使用的模块复制到 modules 目录下，编写配置文件（重要！）

./bin/Akiba  # In Windows, use .\bin\Akiba.bat
```

## 命令行选项

### 基本选项

- `-c`/`--main-config <path>`：设置主配置文件路径。格式为 `<文件路径>@<JSON 路径>`，例如 `configs/config.json@/main`。默认值为 `configs/config.yaml@main`
- `--venv <dir>`：设置全局 Python 虚拟环境（virtual environment）根目录，默认为 `akiba-venv`
- `-h`/`--help`：显示帮助信息并退出
- `-V`/`--version`：输出版本号并退出

### 运行模式选项（互斥，必选其一）

Akiba 支持三种运行模式，这些模式互斥，每次运行只能选择其中一种：

#### 1. 正常分析模式（默认）

不添加任何模式选项时，Akiba 将按照主配置文件中的任务配置，对数据库中的二进制文件进行分析。

#### 2. 导入模式

- `-i`/`--import <config path>`：导入任务模式。指定该选项后，Akiba 将只执行文件导入任务，不进行任何分析工作。需要指定导入配置文件路径。

#### 3. 断点续传模式

- `-r`/`--restore <'latest'/timestamp>`：断点续传模式。当任务因 bug 或其他原因中断时，可使用该模式继续执行未完成的任务。
  - 参数可以是 `latest`（继续最新任务）或时间戳（如 `20250201140000`）
  - **注意**：指定 `-r` 后，`-c` 选项将被忽略。Akiba 会使用中断任务的原始配置（该配置在任务首次运行时已复制到 `log` 目录）
  - **警告**：除非明确知道目的，否则请勿修改 `log` 目录中的配置文件

断点续传模式还支持以下附加选项（与 `-r` 配合使用，互斥）：

- `-f`/`--fail-only`：仅重新处理标记为失败（failed）的程序
- `-e`/`--error-only`：仅重新处理标记为错误（error）的程序

### 子命令

Akiba 还提供以下子命令用于管理数据库实例：

#### 1. instance-create - 创建新的数据库实例

```shell
./bin/Akiba instance-create -n <名称> -u <用户> [其他选项]
```

**必需参数：**
- `-i`/`--name <名称>`：实例名称
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 创建名为 test_instance 的实例，用户为 admin，使用默认主机和端口
./bin/Akiba instance-create -n test_instance -u admin

# 指定 Akiba db daemon 所在主机和端口
./bin/Akiba instance-create -n my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 2. instance-backup - 备份数据库实例

```shell
./bin/Akiba instance-backup -i <实例名> -t <类型> -u <用户> [其他选项]
```

**必需参数：**
- `-i`/`--instance <实例名>`：要备份的实例名称
- `-t`/`--type <类型>`：备份类型，必须为 `full`（完整备份）或 `incr`（增量备份）
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-a`/`--alias <别名>`：备份的别名（用于在配置文件中引用）
- `-d`/`--description <描述>`：备份的描述信息
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 对 test_instance 进行完整备份，别名为 backup_2025
./bin/Akiba instance-backup -i test_instance -t full -u admin -a backup_2025

# 对 my_instance 进行增量备份，带描述
./bin/Akiba instance-backup -i my_instance -t incr -u admin-d "Daily incremental backup"
```

#### 3. instance-restore - 从备份恢复数据库实例

```shell
./bin/Akiba instance-restore -n <新实例名> -l <备份标签> -u <用户> [其他选项]
```

**必需参数：**
- `-i`/`--name <实例名>`：要创建的恢复目标实例名称
- `-l`/`--label <标签>`：备份的标签或别名（来自 instance-backup 的 `-a` 参数）
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 从 backup_2025 备份恢复到新实例 restored_instance
./bin/Akiba instance-restore -n restored_instance -l backup_2025 -u admin

# 从指定主机恢复
./bin/Akiba instance-restore -n new_instance -l daily_backup -u admin -H 192.168.1.100
```

#### 4. instance-start - 启动数据库实例

```shell
./bin/Akiba instance-start -i <实例名> -u <用户> [其他选项]
```

**必需参数：**
- `-i`/`--instance <实例名>`：要启动的实例名称
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 启动名为 test_instance 的实例，用户为 admin
./bin/Akiba instance-start -i test_instance -u admin

# 指定主机和端口启动实例
./bin/Akiba instance-start -i my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 5. instance-shutdown - 关闭数据库实例

```shell
./bin/Akiba instance-shutdown -i <实例名> -u <用户> [其他选项]
```

**必需参数：**
- `-i`/`--instance <实例名>`：要关闭的实例名称
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 关闭名为 test_instance 的实例
./bin/Akiba instance-shutdown -i test_instance -u admin

# 关闭指定主机上的实例
./bin/Akiba instance-shutdown -i my_instance -u admin -H 192.168.1.100
```

#### 6. instance-delete - 删除数据库实例

```shell
./bin/Akiba instance-delete -i <实例名> -u <用户> [其他选项]
```

**必需参数：**
- `-i`/`--instance <实例名>`：要删除的实例名称
- `-u`/`--user <用户名>`：Akiba 数据库用户名

**可选参数：**
- `-P`/`--password`：Akiba 用户密码。如不指定，将在命令行交互提示输入
- `-H`/`--host <主机>`：数据库守护程序主机地址，默认为 `127.0.0.1`
- `-p`/`--port <端口>`：数据库守护程序端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 删除名为 test_instance 的实例
./bin/Akiba instance-delete -i test_instance -u admin

# 删除指定主机和端口的实例
./bin/Akiba instance-delete -i my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 7. server - 启动 Akiba HTTP 服务器

```shell
./bin/Akiba server [选项]
```

**描述：** 启动一个 HTTP 服务器，提供 REST API 端点用于与 Akiba 交互。支持 JWT 认证和用户管理。

**可选参数：**
- `-p`/`--port <端口>`：服务器端口，默认为 `8080`
- `--host <主机>`：服务器主机，默认为 `0.0.0.0`
- `--bin-root <路径>`：二进制文件根目录，默认为 `~/.akiba`
- `--jwt-secret <密钥>`：JWT 密钥，生产环境请修改！
- `--db-host <主机>`：用户管理的 PostgreSQL 主机，默认为 `127.0.0.1`
- `--db-port <端口>`：用户管理的 PostgreSQL 端口，默认为 `5432`
- `--db-name <名称>`：用户管理的数据库名称，默认为 `akiba_users`
- `--db-user <用户>`：PostgreSQL 用户，默认为 `akiba`
- `--db-password <密码>`：PostgreSQL 密码，默认为 `akiba`
- `--daemon-host <主机>`：Akiba DB daemon 主机，默认为 `127.0.0.1`
- `--daemon-port <端口>`：Akiba DB daemon 端口，默认为 `31777`
- `-h`/`--help`：显示帮助信息并退出

**示例：**
```shell
# 使用默认设置启动服务器
./bin/Akiba server

# 使用自定义端口和 JWT 密钥启动服务器
./bin/Akiba server -p 9000 --jwt-secret "my-secure-random-string"
```

#### 8. meltdown - 紧急终止开关

```shell
./bin/Akiba meltdown [-f|--force]
```

**描述：** 紧急终止开关，立即终止所有正在运行的 Akiba 框架进程。当 LLM agent 执行危险操作或需要立即停止所有 Akiba 活动时使用。

**可选参数：**
- `-f`/`--force`：跳过确认提示，直接终止进程

**示例：**
```shell
# 终止前显示确认提示
./bin/Akiba meltdown

# 直接终止，不需确认
./bin/Akiba meltdown --force
```

## LLM Agent 支持

Akiba 提供了内置的 LLM agent 基础设施，用于智能二进制分析。Agent 系统支持：

### Agent 策略

- **ReAct 策略**（默认）：显式的 Thought → Action → Observation 循环，用于逐步推理
- **Plan-Execute 策略**：先制定计划，再执行每个步骤，最后进行反思

### 内置工具

使用 `AgentModule` 时，以下内置工具自动可用：

- `run_module` — 委托工作给另一个 AkibaModule
- `spawn_sub_agent` — 异步生成子 LLM agent（模板或自由）
- `await_multiple_children` — 等待异步子 agent 达到目标状态
- `query_module_data` — 从数据库查询分析结果
- `query_session_history` — 查看过去的 agent 会话
- `query_memories` — 搜索长期记忆存储

### LLM 提供者

Akiba 支持以下 LLM 提供者：

| 提供者 | 显示名称 | OpenAI 兼容 |
|-------|---------|------------|
| `OPEN_AI` | OpenAI | 否 |
| `ANTHROPIC` | Anthropic | 否 |
| `GOOGLE_GEMINI` | Google Gemini | 否 |
| `MISTRAL` | Mistral AI | 否 |
| `OLLAMA` | Ollama | 否 |
| `AZURE_OPEN_AI` | Azure OpenAI | 否 |
| `DEEP_SEEK` | DeepSeek | 是 |
| `MOONSHOT` | Moonshot / Kimi | 是 |
| `ZHIPU` | Zhipu AI / ChatGLM | 是 |
| `QWEN` | Qwen / DashScope | 是 |
| `OPEN_AI_COMPATIBLE` | OpenAI 兼容 | 是 |

### 配置示例

```json
{
  "llm": {
    "provider": "DEEP_SEEK",
    "modelName": "deepseek-v4-flash",
    "apiKeyEnv": "DEEPSEEK_API_KEY"
  }
}
```

### 创建 Agent 模块

继承 `AgentModule` 来创建 agent 驱动的分析模块：

```kotlin
@WithAgentSystemPrompt("You are a binary analysis assistant.")
@WithAgentMaxIterations(15)
@WithTableColumn("analysis", "TEXT")
class BinaryAnalyst(configPath: String? = null, id: Int, program: Program?)
    : AgentModule(configPath, id, program) {

    override fun defineTools(): List<Tool> = listOf(
        tool("list_functions") {
            description = "List all functions in the binary"
            execute { args ->
                program?.functionManager?.getFunctions(true)
                    ?.take(50)?.joinToString("\n") { "${it.name} @ ${it.entryPoint}" }
                    ?: "No program loaded"
            }
        }
    )

    override fun taskPrompt(): String =
        "Analyze this binary and identify its purpose, entry point, and key functions."
}
```

其他配置文件需要保存到 `src/main/resources/configs`。具体内容请查看 [Usage_guide_zh.md](Usage_guide_zh.md)