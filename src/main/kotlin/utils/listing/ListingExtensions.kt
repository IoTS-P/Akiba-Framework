package org.iotsplab.akiba.utils.listing

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Listing
import ghidra.util.task.TaskMonitor

fun Listing.getUndefinedDataRegionAfter(addr: Address): Pair<Address, Long>? {
    val start = this.getUndefinedDataAfter(addr, TaskMonitor.DUMMY)?.address ?: return null

    var ptr = start
    while(true) {
        val nextUndef = this.getUndefinedDataAfter(ptr, TaskMonitor.DUMMY)?.address
        if (nextUndef == null || nextUndef.subtract(start) == 1L)
            return start to (ptr.subtract(start) + 1)
        ptr = nextUndef
    }
}