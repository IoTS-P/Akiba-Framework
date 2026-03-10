package org.iotsplab.akiba.module

abstract class AkibaPackage {
    open val author: String = ""
    open val version: String = "0.1"
    open val description: String = ""

    fun packageInfo(): String {
        return """
            Package: ${this::class.simpleName}
            Full path: ${this::class}
            Author: $author
            Version: $version
            Description: $description
        """.trimIndent()
    }
}