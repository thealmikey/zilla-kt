# Coding Style Guide — Zilla Manager (Kotlin)

This guide captures the repository’s idiomatic patterns and conventions — especially the less-common choices and new-feature usage — so contributors implement changes consistently. It intentionally omits general/common best practices.

---

## High-level architectural patterns
- Pipeline style: long-running operations are built as small functions that return arrow.core.Either and are composed with flatMap / mapLeft to form a failure-aware pipeline.
  - Top-level workflows (e.g. installFromTemplate) orchestrate steps via chained Either.flatMap calls.
- Domain errors use a sealed/domain error type (ZpmResolutionErrorKt) and exceptions are converted to that type at function boundaries via Either.catch { ... }.mapLeft { ... }.
- Functions are small, single-responsibility and prefer returning Either<Error, T> rather than throwing.

---

## Arrow / Either usage (conventions)
- Use Either.catch { ... } to capture exceptions and immediately map them to domain errors with .mapLeft { ... }.
  - Example pattern: Either.catch { /* do work */ }.mapLeft { ZpmResolutionErrorKt.DependencyResolutionError("msg: ${it.message}") }
- Use .right() and .left() to construct success/failure values when there is no exception to convert.
- Aggregate errors into arrow.core.NonEmptyList and return as left via:
  - errors.toNonEmptyListOrNull()?.let { it.left() } ?: Unit.right()
- Use arrow's either { ... } block with .bind() for multi-step operations when convenient:
  - either { imageLinker.link(...).bind(); launcherWriter.write(...).bind() }.mapLeft { ... }
- Use getOrElse inside flatMap chains to handle nested Either-to-value extraction and map domain errors upwards.

---

## Error aggregation strategy
- Collect recoverable errors into a MutableList<String> within a step, then convert to NonEmptyList for returning left when needed.
- Respect `ignoreMissingDependencies` by converting certain errors into warnings/feedback instead of failing the pipeline.

---

## Logging vs User Feedback
- Two-channel logging:
  - logger (SLF4J / KotlinLogging) for internal debug/stack traces.
  - feedback: ((String) -> Unit)? for user-visible progress lines. feedback is optional and frequently includes emoji prefixes (see "UI content").
- All public actions invoke both where appropriate:
  - Use logger.debug for internal state; call feedback?.invoke for messages aimed at users (CLI output).
- Message style: emoji prefixes (`✅`, `❌`, `⚠️`, `📦`, `📝`, `🔗`, `🧪`, `ℹ️`) are used consistently to convey status.

---

## IO / Files / Path usage
- Prefer java.nio.file.Path and kotlin.io.path extension helpers over java.io.File; use Files.* for file operations.
- Use kotlin.io.path.* helpers: createDirectories(), exists(), isReadable(), isWritable(), isRegularFile(), deleteRecursively(), deleteExisting().
- Explicitly handle platform differences:
  - detect Windows via System.getProperty("os.name").lowercase().contains("win")
  - Windows-specific retry delays and permission handling.
- Ensure writeability with a retry loop:
  - create dir, set POSIX perms (Files.setPosixFilePermissions) where supported, sleep and retry on FileSystemException.
  - If Posix perms not supported, fall back to File.setExecutable(true, false) where appropriate.
- Use Files.newOutputStream / Files.copy / Files.move with StandardCopyOption.REPLACE_EXISTING for JAR manipulation and safe moves.

---

## JAR & module handling (Java module system)
- Use java.util.jar.JarFile and JarOutputStream to inspect and rewrite JARs.
- For multi-release JARs: prefer promoting the highest META-INF/versions/X/module-info.class to root if root module-info.class is missing. Implementation pattern:
  - scan entries → find best versioned module-info → extract bytes → rewrite JAR excluding META-INF/versions/* and add module-info.class at root.
- Use java.lang.module.ModuleFinder to detect modules and descriptors; use ModuleDescriptor APIs to create stub ModuleDescriptor when generating synthetic artifacts.
- For generating/compiling module-info:
  - Use ToolProvider (jdeps, javac) to run tools programmatically.
  - Capture tool output via ByteArrayOutputStream + PrintStream; check exit codes, surface stderr to feedback.
  - Prefer adding `-proc:none` when javac >= 21 (use a helper atLeastVersion(tool, 21) that runs tool.run("--version") and regex-parses the major version).
- When extending JARs with module-info, always set deterministic entry time using JarEntry.apply { time = 318240000000L } to avoid spurious diffs.

---

## Process and external tool invocation
- Prefer ToolProvider.findFirst("tool") and tool.run(PrintStream(out), PrintStream(err), *args) over ProcessBuilder when the JDK tool API is available.
- When using ProcessBuilder (javac/jar for small ad-hoc tasks like java.inject stub), prefer .inheritIO() where interactive console output is acceptable.
- Capture outputs into ByteArrayOutputStream for logging and include them in feedback when non-empty.
- Always check exit codes and map failures to domain errors with informative messages (including stderr content).

---

## Kotlin language & API idioms
- Use default constructor parameters for dependency injection: dryRun, feedback, jarCopier, moduleInfoGenerator, etc.
- Use data class .copy() to make small adjustments to module-like objects (e.g., m.copy(delegating = false)).
- Use pair destructuring for multi-value results:
  - val (realModuleInfo, entryName) = when { ... }
- Use buildString { ... } to produce multi-line content (module-info.java generation, assembly xml, patched module-info).
- Use multiline string literals with trimIndent()/trimMargin() for generated file content (escape `$` as needed in templates).
- Use sequences (.asSequence()) when iterating jar.entries() to avoid creating large intermediate collections.
- Use apply/also for in-place initialization of Java objects (JarEntry(...).apply { time = ... }).

---

## Naming and domain conventions
- Domain classes use suffix `Kt` in interop/generated names (ZpmArtifactKt, ZpmModuleKt, ZpmResolutionErrorKt). Treat these as canonical domain types.
- Use a special delegate module name constant: ZpmModuleKt.DELEGATE_NAME; prefer to look up or create delegate via modules.find { it.name == ... } ?: ZpmModuleKt()
- Use `delegate` as the name for the module that aggregates automatic modules; special handling phases: migrateUnnamed → generateSystemOnlyAutomatic → delegateAutomatic → processModules → generateDelegating.

---

## Concurrency / caching patterns
- Use a concurrent set for seen artifacts:
  - ConcurrentHashMap.newKeySet<String>() to avoid duplicate work across resolution steps.
- Cache artifacts into a custom ZPM cache directory and use it preferentially over local m2 repository.

---

## Dry-run pattern
- Pass `dryRun: Boolean` down into operations; functions should:
  - Check dryRun early and return the expected path/result without performing destructive actions.
  - Emit a feedback line prefixed with "🧪 [dry-run]".

---

## Version handling
- Use a simple numeric-version comparator implemented locally (compareVersions) for deduplication decisions. Keep it simple: split on '.', parse ints, compare numeric components.
- Where more accurate parsing is needed, call out the limitation in comments and treat qualifiers (e.g., -SNAPSHOT) as zeros.

---

## Error-to-feedback mapping
- All error conversions call feedback?.invoke and logger.error (or warn) before returning a left to provide both CLI and log visibility.
- Map low-level exceptions to ZpmResolutionErrorKt.DependencyResolutionError with contextual message.

---

## Small but useful conventions
- Use explicit Regex patterns for coordinate validation and for parsing tool versions.
- Use short, clear emoji-prefixed feedback messages; keep them consistent across codebase.
- Keep deterministic timestamps for JAR entries to make builds reproducible.
- When removing or modifying artifacts on disk, try to clean up intermediate generated directories on error (deleteRecursively in catch blocks).
- Prefer returning Either<ZpmResolutionErrorKt, Unit> for operations that don't produce a meaningful result.

---

If you plan to add new utilities or modify flows, follow these rules:
- Keep functions returning Either and compose them.
- Surface all CLI-facing messages via the feedback lambda (do not write directly to stdout except through feedback).
- Convert thrown exceptions into domain errors at the boundary and include contextual text for easier debugging.
- Reuse existing patterns for ToolProvider usage and JAR/module handling so behavior (capture of outputs, checks for exitCode, deterministic jar times, POSIX perms) remains consistent.