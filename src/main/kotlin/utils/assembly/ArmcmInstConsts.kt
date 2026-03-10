package org.iotsplab.akiba.utils.assembly

object ArmcmInstConsts {
    val OPERAND_SIZE_1_MNEMONICS: Set<String> = setOf("LDRB", "LDRSB", "STRB", "LDRBT", "LDRSBT", "STRBT")
    val OPERAND_SIZE_2_MNEMONICS: Set<String> = setOf("LDRH", "LDRSH", "STRH", "LDRST", "LDRSHT", "STRHT")
}