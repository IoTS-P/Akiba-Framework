package org.iotsplab.akiba.plan

/** Project lifecycle as a script sees it. */
interface ProjectVerbs {

    /** Fails with [ProjectExistsException] when the name is taken. */
    fun createProject(name: String, overwrite: Boolean = false): Outcome<ProjectRef>

    /** Reuses an existing project, creates it otherwise. */
    fun createOrOpenProject(name: String): Outcome<ProjectRef>

    /** Requires the project to exist; a typo is an error rather than a new batch. */
    fun openProject(name: String): Outcome<ProjectRef>
}

internal class ProjectOps(
    private val runtime: PlanRuntime,
    private val support: ScopeSupport,
) : ProjectVerbs {

    override fun createProject(name: String, overwrite: Boolean): Outcome<ProjectRef> =
        support.step("createProject", mapOf("project" to name, "overwrite" to overwrite)) {
            if (!overwrite && runtime.projectExists(name)) throw ProjectExistsException(name)
            runtime.createProject(name, overwrite)
        }

    override fun createOrOpenProject(name: String): Outcome<ProjectRef> =
        support.step("createOrOpenProject", mapOf("project" to name)) {
            runtime.openProject(name, createIfMissing = true)
        }

    override fun openProject(name: String): Outcome<ProjectRef> =
        support.step("openProject", mapOf("project" to name)) {
            if (!runtime.projectExists(name)) throw ProjectExistsException("$name (missing)")
            runtime.openProject(name, createIfMissing = false)
        }
}
