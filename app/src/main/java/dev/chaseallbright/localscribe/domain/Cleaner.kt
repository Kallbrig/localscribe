package dev.chaseallbright.localscribe.domain

fun interface Cleaner {
    fun clean(text: String, mode: CleanupMode, vocabulary: List<String>): CleanResult
}
