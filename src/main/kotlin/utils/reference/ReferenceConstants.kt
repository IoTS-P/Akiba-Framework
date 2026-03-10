package org.iotsplab.akiba.utils.reference

import ghidra.program.model.symbol.RefType

object ReferenceConstants {
    val REFERENCE_CALL = setOf(
        RefType.UNCONDITIONAL_CALL, RefType.CONDITIONAL_CALL, RefType.CONDITIONAL_COMPUTED_CALL,
        RefType.COMPUTED_CALL)

    val REFERENCE_JUMP = setOf(
        RefType.UNCONDITIONAL_JUMP, RefType.CONDITIONAL_JUMP, RefType.CONDITIONAL_COMPUTED_JUMP,
        RefType.COMPUTED_JUMP
    )

    val REFERENCE_READ = setOf(
        RefType.READ, RefType.READ_WRITE, RefType.READ_IND, RefType.READ_WRITE_IND
    )

    val REFERENCE_WRITE = setOf(
        RefType.WRITE, RefType.READ_WRITE, RefType.WRITE_IND, RefType.READ_WRITE_IND
    )
}