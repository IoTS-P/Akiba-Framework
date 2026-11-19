# Plan DSL

A plan runs modules over programs without you writing a Kotlin program. The same plan can be
written as a Kotlin script or as JSON; both describe the same operations.

```shell
akiba -p plan.kts                  # a Kotlin script
akiba -c plan.json                 # a JSON plan
```

`-p` and `-c` are mutually exclusive, and at most one may be given. A plan requires a database
connection (`--db-*`) because programs and results live there.

## How a plan executes

Every verb runs **immediately** and returns an `Outcome`. There is no build phase and no staged
plan object: the script *is* the execution order.

An `Outcome` is either a value or a failure. Failures surface as exceptions with a message and an
exit code, so ordinary `try`/`catch` works:

```kotlin
val scanned = try {
    run.runAll(programs, ModuleSpec("ZeroTrimmer")).value
} catch (e: Exception) {
    println("nothing was scanned: ${e.message}")
    exitProcess(1)
}
```

The run journal (`run.jsonl`) records each step as it happens.

## Verbs

### Projects

| Verb | Result |
|---|---|
| `project.createProject(name, overwrite = false)` | `ProjectRef` — fails if it exists and `overwrite` is false |
| `project.createOrOpenProject(name)` | `ProjectRef` |
| `project.openProject(name)` | `ProjectRef` — fails if missing |

### Programs

| Verb | Result |
|---|---|
| `program.programs(filter = "")` | `List<ProgramHandle>`; the filter is bare SQL over the registry |
| `program.importAll(programs, target)` | how many were imported |

`ProgramHandle` carries `id`, `name`, `arch`, `sizeBytes`, `path`, `project` and `binaryId`.

### Binaries

| Verb | Result |
|---|---|
| `binary.binaries(filter = "")` | `List<BinaryHandle>` |
| `binary.importBinary(path)` | `BinaryHandle`; a duplicate raises `BinaryExistsException` |
| `binary.importBinaries(paths)` | `List<BinaryHandle>` |

Importing here means **registering the file in Akiba's store** and receiving an id — not opening it
in a Ghidra project (`program.importAll` does that).

### Running modules

| Verb | Result |
|---|---|
| `run.runAll(programs, spec)` | `Map<programId, ModuleRun>` — one spec for every program |
| `run.runAll(programs, specFor)` | same, but the spec is chosen per program |
| `run.saveResult(spec, run)` | `Boolean`; stores what `result()` returned |

`ModuleSpec(className, timeoutSeconds = null, config = null, writable = false)`:

- `config` reaches the module through reflection; whatever it declares is injected
- `timeoutSeconds` of `0` or `null` means no limit; a timed-out run finishes with exit code 3
- `writable = true` opens the program for writing and saves it after the run

A module may run at most once at a time for the same target. A second concurrent run is refused;
sequential runs overwrite.

### Results

A module returns `Map<String, Any?>`. The keys must be columns that exist in the module's table —
an unknown key is a bug in the module, not something to store. Small results (up to 1 KB) come back
inline; larger ones are written under `~/.akiba` and the path is reported.

```kotlin
val runs = run.runAll(programs, ModuleSpec("ZeroTrimmer")).value
for ((programId, module) in runs) {
    println("$programId -> ${module.result()}")
    run.saveResult(ModuleSpec("ZeroTrimmer"), module)
}
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | the plan itself failed (script error, refused call) |
| 2 | usage error (bad arguments, missing connection) |
| 3 | a module timed out |
| 4 | a module failed |
| 5 | framework error |
| ≥ 100 | reserved for a module's own meaning |

## The same plan in JSON

```json
{
  "steps": [
    { "verb": "programs", "filter": "project = 'test'" },
    { "verb": "runAll", "module": "ZeroTrimmer" }
  ]
}
```

JSON covers the same verbs with the same arguments; it exists for cases where a script is awkward,
such as generated plans. Anything the JSON plan can do, the Kotlin plan can do with more control —
`specFor`, `try`/`catch`, and computing arguments as you go.

## Workspaces

A module writes into its program's workspace (`<workspaceRoot>/<project>/programs/<path>/<class>/`).
When a module is run for a conversation, it writes inside that conversation's own directory instead,
in a timestamped subdirectory, so two calls never overwrite each other.
