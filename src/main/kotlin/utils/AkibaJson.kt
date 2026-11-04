package org.iotsplab.akiba.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * The one JSON mapper shared by the framework.
 *
 * Sites that need extra configuration (`registerModule`, `configure`, …) must
 * build their own instance — mutating this one would change behaviour for
 * every other caller.
 */
object AkibaJson {
    /** Thread-safe for read/write once configured; do not mutate. */
    val mapper: ObjectMapper = jacksonObjectMapper()
}
