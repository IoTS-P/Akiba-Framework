package org.iotsplab.akiba.utils.reference

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.program.model.symbol.RefType
import ghidra.program.model.symbol.Reference

fun Reference.isValidCodeTarget(): Boolean {
    return referenceType in listOf(
        RefType.UNCONDITIONAL_CALL, RefType.CONDITIONAL_CALL, RefType.CONDITIONAL_COMPUTED_CALL,
        RefType.COMPUTED_CALL, RefType.CONDITIONAL_JUMP, RefType.UNCONDITIONAL_JUMP,
        RefType.COMPUTED_JUMP
    )
}

fun Reference.isPossibleFunctionStart(): Boolean {
    return referenceType in ReferenceConstants.REFERENCE_CALL
}

fun Address.isValidCodeTarget(program: Program): Boolean {
    return program.referenceManager.getReferencesTo(this).any { it.isValidCodeTarget() }
}