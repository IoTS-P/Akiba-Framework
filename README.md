**Note: Please do not clone this repository separately. Use the following command to clone the entire Akiba project:**

```shell
git clone https://github.com/IoTS-P/Akiba.git
cd Akiba
git submodule update --init --recursive
```

# Akiba Runtime Framework

The Akiba Runtime Framework is the runtime entry point for the Akiba tool.

## How to Run

### Ghidra Preprocessing

1. Download Ghidra 12.1 from here: [Link](https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.1_build/ghidra_12.1_PUBLIC_20260513.zip)
2. Extract Ghidra.zip
3. (Optional but may be required by some modules) Extract some Ghidra official extensions (zip files) to `/Ghidra/Features`, so that Ghidra can package these extensions into `ghidra.jar` when building the JAR (by default, the `MachineLearning` extension is packaged)
4. Go to the `/support` directory and run the `buildGhidraJar` script file (run `buildGhidraJar.bat` on Windows)
5. If everything goes well, you can find the `ghidra.jar` file in `/support`. This is one of the dependencies Akiba needs. Copy it to `/path/to/Akiba/lib`

Note: Akiba has so far only been verified on x64. If you need to run Ghidra and Akiba on a non-x64 processor, first go to the `support/gradle` subdirectory under the Ghidra root and run:
```shell
gradle buildNatives
```
On macOS Apple Silicon, the command above may not succeed out of the box. The workarounds below are provided for reference:

#### Missing `binutils-2.41.tar.bz2`

The `GnuDisassembler` extension needs the GNU binutils source code in order to compile `gdis`. This archive is not shipped with the Ghidra release and must be downloaded manually.

Workaround: download it from https://ftp.gnu.org/pub/gnu/binutils/binutils-2.41.tar.bz2 and place it under `Ghidra/Extensions/GnuDisassembler/`.

#### `fdopen` macro in zlib conflicts with the macOS SDK

In the zlib bundled with binutils 2.41, `zutil.h` defines `#define fdopen(fd,mode) NULL` whenever `TARGET_OS_MAC` is detected. This collides with the `fdopen` function declaration in `_stdio.h` shipped by recent macOS SDKs (Xcode / Command Line Tools).

Workaround: edit `build/binutils-2.41/zlib/zutil.h` and guard that macro definition with `!defined(__APPLE__)` so the broken macro is not introduced under modern Apple toolchains.

#### Missing `makeinfo` tool

Building binutils' documentation requires `makeinfo` (from the `texinfo` package), which is not installed on macOS by default.

Workaround: install it via `brew install texinfo`.

### Build

Build the Akiba Database Daemon, Akiba Framework, and Akiba Modules with a single command:

```shell
# Run from the Akiba root directory
./gradlew assemble  # On Windows, use gradlew.bat
```

### Run

```shell
cd build/distributions
unzip Akiba-<version>.zip
cd Akiba-<version>

# Copy the modules you need to use into the modules directory and write configuration files (Important!)

./bin/Akiba  # In Windows, use .\bin\Akiba.bat
```

## Command Line Options

### Basic Options

- `-c`/`--main-config <path>`: Set the main configuration file path. Format is `<file path>@<JSON path>`, e.g., `configs/config.json@/main`. Default value is `configs/config.yaml@main`
- `--venv <dir>`: Set the global Python virtual environment root directory, default is `akiba-venv`
- `-h`/`--help`: Show help message and exit
- `-V`/`--version`: Output version number and exit

### Runtime Mode Options (Mutually Exclusive, Choose One)

Akiba supports three runtime modes, which are mutually exclusive. Only one mode can be selected per run:

#### 1. Normal Analysis Mode (Default)

When no mode option is specified, Akiba will analyze the binary files in the database according to the task configuration in the main configuration file.

#### 2. Import Mode

- `-i`/`--import <config path>`: Import task mode. When this option is specified, Akiba will only perform file import tasks without any analysis work. An import configuration file path must be provided.

#### 3. Restore Mode

- `-r`/`--restore <'latest'/timestamp>`: Restore mode. When a task is interrupted due to bugs or other reasons, this mode can be used to continue the unfinished task.
  - The parameter can be `latest` (continue the latest task) or a timestamp (e.g., `20250201140000`)
  - **Note**: After specifying `-r`, the `-c` option will be ignored. Akiba will use the original configuration of the interrupted task (this configuration is copied to the `log` directory when the task first runs)
  - **Warning**: Unless you know exactly what you're doing, do not modify the configuration files in the `log` directory

Restore mode also supports the following additional options (used with `-r`, mutually exclusive):

- `-f`/`--fail-only`: Only reprocess programs marked as failed
- `-e`/`--error-only`: Only reprocess programs marked with errors

### Subcommands

Akiba also provides the following subcommands for managing database instances:

#### 1. instance-create - Create a New Database Instance

```shell
./bin/Akiba instance-create -n <name> -u <user> [other options]
```

**Required Parameters:**
- `-n`/`--name <name>`: Instance name
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Create an instance named test_instance with user admin, using default host and port
./bin/Akiba instance-create -n test_instance -u admin

# Specify host and port
./bin/Akiba instance-create -n my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 2. instance-backup - Backup a Database Instance

```shell
./bin/Akiba instance-backup -i <instance> -t <type> -u <user> [other options]
```

**Required Parameters:**
- `-i`/`--instance <instance>`: Name of the instance to backup
- `-t`/`--type <type>`: Backup type, must be `full` (full backup) or `incr` (incremental backup)
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-a`/`--alias <alias>`: Alias of the backup (for referencing in configuration files)
- `-d`/`--description <description>`: Description of the backup
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Perform a full backup on test_instance with alias backup_2025
./bin/Akiba instance-backup -i test_instance -t full -u admin -a backup_2025

# Perform an incremental backup on my_instance with description
./bin/Akiba instance-backup -i my_instance -t incr -u admin-d "Daily incremental backup"
```

#### 3. instance-restore - Restore a Database Instance from Backup

```shell
./bin/Akiba instance-restore -n <name> -l <label> -u <user> [other options]
```

**Required Parameters:**
- `-n`/`--name <name>`: Name of the restoration target instance to create
- `-l`/`--label <label>`: Label or alias of the backup (from instance-backup's `-a` parameter)
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Restore from backup_2025 to a new instance restored_instance
./bin/Akiba instance-restore -n restored_instance -l backup_2025 -u admin

# Restore from a specific host
./bin/Akiba instance-restore -n new_instance -l daily_backup -u admin -H 192.168.1.100
```

#### 4. instance-start - Start a Database Instance

```shell
./bin/Akiba instance-start -i <instance> -u <user> [other options]
```

**Required Parameters:**
- `-i`/`--instance <instance>`: Name of the instance to start
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Start an instance named test_instance with user admin
./bin/Akiba instance-start -i test_instance -u admin

# Start an instance with specified host and port
./bin/Akiba instance-start -i my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 5. instance-shutdown - Shut Down a Database Instance

```shell
./bin/Akiba instance-shutdown -i <instance> -u <user> [other options]
```

**Required Parameters:**
- `-i`/`--instance <instance>`: Name of the instance to shut down
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Shut down an instance named test_instance
./bin/Akiba instance-shutdown -i test_instance -u admin

# Shut down an instance on a specific host
./bin/Akiba instance-shutdown -i my_instance -u admin -H 192.168.1.100
```

#### 6. instance-delete - Delete a Database Instance

```shell
./bin/Akiba instance-delete -i <instance> -u <user> [other options]
```

**Required Parameters:**
- `-i`/`--instance <instance>`: Name of the instance to delete
- `-u`/`--user <username>`: Akiba database username

**Optional Parameters:**
- `-P`/`--password`: Akiba user password. If not specified, will be prompted interactively
- `-H`/`--host <host>`: Database daemon host address, default is `127.0.0.1`
- `-p`/`--port <port>`: Database daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Delete an instance named test_instance
./bin/Akiba instance-delete -i test_instance -u admin

# Delete an instance on a specific host and port
./bin/Akiba instance-delete -i my_instance -u admin -H 192.168.1.100 -p 31777
```

#### 7. server - Start Akiba HTTP Server

```shell
./bin/Akiba server [options]
```

**Description:** Starts an HTTP server that provides REST API endpoints for interacting with Akiba. Supports JWT authentication and user management.

**Optional Parameters:**
- `-p`/`--port <port>`: Server port, default is `8080`
- `--host <host>`: Server host, default is `0.0.0.0`
- `--bin-root <path>`: Root directory for binary files, default is `~/.akiba`
- `--jwt-secret <secret>`: JWT secret key, default is a placeholder string (change in production!)
- `--db-host <host>`: PostgreSQL host for user management, default is `127.0.0.1`
- `--db-port <port>`: PostgreSQL port for user management, default is `5432`
- `--db-name <name>`: Database name for user management, default is `akiba_users`
- `--db-user <user>`: PostgreSQL user, default is `akiba`
- `--db-password <password>`: PostgreSQL password, default is `akiba`
- `--daemon-host <host>`: Akiba DB daemon host, default is `127.0.0.1`
- `--daemon-port <port>`: Akiba DB daemon port, default is `31777`
- `-h`/`--help`: Show help message and exit

**Examples:**
```shell
# Start server with default settings
./bin/Akiba server

# Start server on custom port with custom JWT secret
./bin/Akiba server -p 9000 --jwt-secret "my-secure-random-string"
```

#### 8. meltdown - Emergency Kill Switch

```shell
./bin/Akiba meltdown [-f|--force]
```

**Description:** Emergency kill switch that immediately terminates ALL running Akiba framework processes. Use when an LLM agent is executing dangerous operations or when you need to halt all Akiba activity immediately.

**Optional Parameters:**
- `-f`/`--force`: Skip the confirmation prompt and kill processes immediately

**Examples:**
```shell
# Show confirmation prompt before killing
./bin/Akiba meltdown

# Kill immediately without confirmation
./bin/Akiba meltdown --force
```

## LLM Agent Support

Akiba provides a built-in LLM agent infrastructure for intelligent binary analysis. The agent system supports:

### Agent Strategies

- **ReAct Strategy** (default): Explicit Thought → Action → Observation cycle for step-by-step reasoning
- **Plan-Execute Strategy**: Plan first, then execute each step, with reflection phase

### Built-in Tools

When using `AgentModule`, the following built-in tools are automatically available:

- `run_module` — Delegate work to another AkibaModule
- `spawn_sub_agent` — Async-spawn a child LLM agent (template or freeform)
- `await_agent` — Wait for an async child to reach a target state
- `query_module_data` — Query analysis results from the database
- `query_session_history` — Review past agent sessions
- `query_memories` — Search the long-term memory store

### LLM Providers

Akiba supports the following LLM providers:

| Provider | Display Name | OpenAI-Compatible |
|----------|-------------|-------------------|
| `OPEN_AI` | OpenAI | No |
| `ANTHROPIC` | Anthropic | No |
| `GOOGLE_GEMINI` | Google Gemini | No |
| `MISTRAL` | Mistral AI | No |
| `OLLAMA` | Ollama | No |
| `AZURE_OPEN_AI` | Azure OpenAI | No |
| `DEEP_SEEK` | DeepSeek | Yes |
| `MOONSHOT` | Moonshot / Kimi | Yes |
| `ZHIPU` | Zhipu AI / ChatGLM | Yes |
| `QWEN` | Qwen / DashScope | Yes |
| `OPEN_AI_COMPATIBLE` | OpenAI-Compatible | Yes |

### Configuration Example

```json
{
  "llm": {
    "provider": "DEEP_SEEK",
    "modelName": "deepseek-v4-flash",
    "apiKeyEnv": "DEEPSEEK_API_KEY"
  }
}
```

### Creating an Agent Module

Extend `AgentModule` to create an agent-driven analysis module:

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

Other configuration files need to be saved in `src/main/resources/configs`. For details, see [Usage_guide_en.md](Usage_guide_en.md)
