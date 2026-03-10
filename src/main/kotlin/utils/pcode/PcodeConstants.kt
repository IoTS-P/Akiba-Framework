package org.iotsplab.akiba.utils.pcode

import ghidra.program.model.pcode.PcodeOp

object PcodeConstants {
    val OPCODE_SYMBOLS: Map<Int, String> = mapOf(
        PcodeOp.INT_ADD to "+", PcodeOp.FLOAT_ADD to "f+",
        PcodeOp.INT_SUB to "-", PcodeOp.FLOAT_SUB to "f-",
        PcodeOp.INT_MULT to "*", PcodeOp.FLOAT_MULT to "f*",
        PcodeOp.INT_DIV to "/", PcodeOp.FLOAT_DIV to "f/",
        PcodeOp.INT_REM to "%", PcodeOp.INT_SREM to "s%",
        PcodeOp.INT_AND to "&", PcodeOp.BOOL_AND to "&&",
        PcodeOp.INT_OR to "|", PcodeOp.BOOL_OR to "||",
        PcodeOp.INT_XOR to "^", PcodeOp.BOOL_XOR to "^^",
        PcodeOp.INT_LEFT to "<<", PcodeOp.INT_RIGHT to ">>",
        PcodeOp.INT_SRIGHT to "s>>",
        PcodeOp.INT_EQUAL to "==", PcodeOp.FLOAT_EQUAL to "f==",
        PcodeOp.INT_NOTEQUAL to "!=", PcodeOp.FLOAT_NOTEQUAL to "f!=",
        PcodeOp.INT_LESS to "<", PcodeOp.FLOAT_LESS to "f<",
        PcodeOp.INT_LESSEQUAL to "<=", PcodeOp.FLOAT_LESSEQUAL to "f<=",
        PcodeOp.INT_SLESS to "s<",
        PcodeOp.INT_SLESSEQUAL to "s<=",
        PcodeOp.INT_ZEXT to "zext",
        PcodeOp.INT_SEXT to "sext",
        PcodeOp.INT_NEGATE to "~", PcodeOp.BOOL_NEGATE to "!",
        PcodeOp.INT_2COMP to "-", PcodeOp.FLOAT_NEG to "f-",
        PcodeOp.FLOAT_ABS to "abs",
        PcodeOp.FLOAT_SQRT to "sqrt",
        PcodeOp.FLOAT_CEIL to "ceil",
        PcodeOp.FLOAT_FLOOR to "floor",
        PcodeOp.FLOAT_ROUND to "round",
        PcodeOp.FLOAT_TRUNC to "trunc",
        PcodeOp.FLOAT_NAN to "nan",
        PcodeOp.FLOAT_FLOAT2FLOAT to "float2float",
        PcodeOp.FLOAT_INT2FLOAT to "int2float",
        PcodeOp.INT_CARRY to "carry", PcodeOp.INT_SCARRY to "scarry",
        PcodeOp.INT_SBORROW to "sborrow",
        PcodeOp.PIECE to "piece", PcodeOp.SUBPIECE to "subpiece",
        PcodeOp.POPCOUNT to "popcount", PcodeOp.LZCOUNT to "lzcount"
    )

    val UNARY_OPCODES: Set<Int> = setOf(
        PcodeOp.INT_2COMP,
        PcodeOp.INT_NEGATE,
        PcodeOp.BOOL_NEGATE,
        PcodeOp.FLOAT_NEG
    )

    val BINARY_OPCODES: Set<Int> = setOf(
        PcodeOp.INT_ADD, PcodeOp.FLOAT_ADD, PcodeOp.INT_SUB, PcodeOp.FLOAT_SUB,
        PcodeOp.INT_MULT, PcodeOp.FLOAT_MULT, PcodeOp.INT_DIV, PcodeOp.FLOAT_DIV,
        PcodeOp.INT_REM, PcodeOp.INT_SREM,
        PcodeOp.INT_AND, PcodeOp.BOOL_AND, PcodeOp.INT_OR, PcodeOp.BOOL_OR,
        PcodeOp.INT_XOR, PcodeOp.BOOL_XOR,
        PcodeOp.INT_LEFT, PcodeOp.INT_RIGHT, PcodeOp.INT_SRIGHT,
        PcodeOp.INT_EQUAL, PcodeOp.FLOAT_EQUAL, PcodeOp.INT_NOTEQUAL, PcodeOp.FLOAT_NOTEQUAL,
        PcodeOp.INT_LESS, PcodeOp.FLOAT_LESS, PcodeOp.INT_LESSEQUAL, PcodeOp.FLOAT_LESSEQUAL,
        PcodeOp.INT_SLESS, PcodeOp.INT_SLESSEQUAL
    )

    val FUNC_UNARY_OPCODES: Set<Int> = setOf(
        PcodeOp.FLOAT_NAN,
        PcodeOp.FLOAT_ABS, PcodeOp.FLOAT_SQRT,
        PcodeOp.FLOAT_CEIL, PcodeOp.FLOAT_FLOOR, PcodeOp.FLOAT_ROUND,
        PcodeOp.POPCOUNT, PcodeOp.LZCOUNT
    )

    val FUNC_BINARY_OPCODES: Set<Int> = setOf(
        PcodeOp.INT_CARRY, PcodeOp.INT_SCARRY, PcodeOp.INT_SBORROW,
        PcodeOp.PIECE, PcodeOp.SUBPIECE
    )

    val FUNC_UNARY_OPCODES_WITH_SIZE: Set<Int> = setOf(
        PcodeOp.INT_ZEXT, PcodeOp.INT_SEXT, PcodeOp.FLOAT_TRUNC,
        PcodeOp.FLOAT_INT2FLOAT, PcodeOp.FLOAT_FLOAT2FLOAT
    )

    val JUMP_OPCODES: Set<Int> = setOf(
        PcodeOp.CALL, PcodeOp.CALLIND, PcodeOp.CALLOTHER,
        PcodeOp.BRANCH, PcodeOp.BRANCHIND, PcodeOp.CBRANCH, PcodeOp.RETURN
    )
}