package org.iotsplab.akiba.utils.abstractFinder

abstract class SearchResult<T>(val value: T) {
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return value == (other as SearchResult<*>).value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }
    
    override fun toString(): String {
        return "${javaClass.simpleName}: ${value.toString()}"
    }
}