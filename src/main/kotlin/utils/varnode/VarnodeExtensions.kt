package org.iotsplab.akiba.utils.varnode

import ghidra.program.model.listing.Program
import ghidra.program.model.pcode.Varnode

@Throws(UnsupportedOperationException::class)
fun Varnode.toPrettyString(program: Program? = null): String {
    return when {
        isAddress -> "A_${address.addressSpace.name}" +
                ":${address.offset.toString(16)}_${size}"
        isRegister -> "R_${program?.getRegister(this)?:offset.toString(16)}" +
                "_${size}"
        isUnique -> "U_${offset.toString(16)}_${size}"
        isConstant -> "${offset.toString(16)}(${
            when (size) {
                1 -> "8"
                2 -> "16"
                4 -> "32"
                8 -> "64"
                16 -> "128"
                else -> throw UnsupportedOperationException("Unexpected size: $size")
            }
        })"
        else -> throw UnsupportedOperationException("Unexpected node: $this")
    }
}