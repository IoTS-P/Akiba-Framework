package org.iotsplab.akiba.plan

/** Asking which programs exist. */
interface QueryVerbs {

    /**
     * Programs matching [filter] — a bare SQL condition over the program index, for
     * example `arch = 'x86' AND size > 4096`. Empty matches everything.
     */
    fun programs(filter: String = ""): Outcome<List<ProgramHandle>>
}

internal class QueryOps(
    private val runtime: ProgramRuntime,
    private val support: ScopeSupport,
) : QueryVerbs {

    override fun programs(filter: String): Outcome<List<ProgramHandle>> =
        support.step("programs", mapOf("filter" to filter)) {
            runtime.findPrograms(filter)
        }
}
