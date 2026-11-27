package io.github.sammynashed.emran.data.hadith

import android.content.Context
import io.github.sammynashed.emran.data.packs.PackStatus
import io.github.sammynashed.emran.data.packs.Packs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed class PackState {
    object Idle : PackState()
    data class Downloading(val fraction: Float) : PackState()
    data class Failed(val reason: String) : PackState()
}

/**
 * The hadith books are optional downloads handled by [Packs] (pack id "hadith-<book>"): checked against a compiled-in
 * sha256, kept in the app's private storage where updates never touch them, and removable from here or from Storage
 * in Settings. This keeps the old names the Hadith screens use.
 */
object HadithPackStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val states: StateFlow<Map<String, PackState>> = Packs.status
        .map { all ->
            all.filterKeys { it.startsWith("hadith-") }.mapKeys { it.key.removePrefix("hadith-") }.mapValues { (_, v) ->
                when (v) {
                    is PackStatus.Downloading -> PackState.Downloading(if (v.total > 0) v.done.toFloat() / v.total else 0f)
                    is PackStatus.Failed -> PackState.Failed(v.reason)
                    PackStatus.Idle -> PackState.Idle
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun start(context: Context, book: HadithBook) = Packs.start(context, "hadith-${book.id}")

    fun delete(context: Context, book: HadithBook) {
        HadithLibrary.release(book.id)
        Packs.delete(context, "hadith-${book.id}")
    }
}
