package io.github.sammynashed.emran.data

/** Remote services run by anyone else are switched off for good: every such call stops here. */
object NoRemote {
    fun block(): Nothing = throw java.io.IOException("remote services are disabled")
}
