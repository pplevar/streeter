package com.streeter.data.engine

fun interface TransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}
