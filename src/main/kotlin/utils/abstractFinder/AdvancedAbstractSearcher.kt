package org.iotsplab.akiba.utils.abstractFinder

abstract class AdvancedAbstractSearcher<T> {
    @Throws(Exception::class)
    abstract fun search(): List<T>
}