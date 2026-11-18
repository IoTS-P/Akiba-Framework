package org.iotsplab.akiba.plan

/**
 * Bringing files into the database, and asking which are already there.
 *
 * This is the plan's half of what a JSON task spells as `import`: the file is registered,
 * copied into the store and made read-only, and the row it got is handed back so the
 * script can run modules against it without a second lookup.
 */
interface BinaryVerbs {

    /**
     * Binaries matching [filter] — a bare SQL condition over the `binaries` table, for
     * example `size > 1048576 AND arch LIKE 'ARM%'`. Empty matches everything.
     */
    fun binaries(filter: String = ""): Outcome<List<BinaryHandle>>

    /**
     * Registers one file and returns the row it was given.
     *
     * The original file is left where it is; the copy under the binaries root is the one
     * every module reads from then on.
     */
    fun importBinary(path: String): Outcome<BinaryHandle>

    /**
     * Registers [paths] in order, giving up at the first file that cannot be imported.
     *
     * Stopping there keeps the failure attached to a name: a half-imported batch that
     * reported "some of these failed" would leave the script guessing which ones.
     */
    fun importBinaries(paths: List<String>): Outcome<List<BinaryHandle>>
}

internal class BinaryOps(
    private val runtime: BinaryRuntime,
    private val support: ScopeSupport,
) : BinaryVerbs {

    override fun binaries(filter: String): Outcome<List<BinaryHandle>> =
        support.step("binaries", mapOf("filter" to filter)) {
            runtime.findBinaries(filter)
        }

    override fun importBinary(path: String): Outcome<BinaryHandle> =
        support.step("importBinary", mapOf("path" to path)) {
            runtime.importBinary(path)
        }

    override fun importBinaries(paths: List<String>): Outcome<List<BinaryHandle>> =
        support.step("importBinaries", mapOf("count" to paths.size)) {
            paths.map { runtime.importBinary(it) }
        }
}
