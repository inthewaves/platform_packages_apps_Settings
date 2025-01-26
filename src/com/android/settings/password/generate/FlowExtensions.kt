package com.android.settings.password.generate

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch

fun <T> CoroutineScope.launchAndCollect(flow: Flow<T>, collector: FlowCollector<T>) {
    launch {
        flow.collect(collector)
    }
}

// Lifecycle-aware flow collection for updating UI code
fun <T> LifecycleOwner.repeatCollectOnLifecycle(
    flow: Flow<T>,
    state: Lifecycle.State = Lifecycle.State.STARTED,
    collector: FlowCollector<T>
) {
    lifecycleScope.launch {
        // Cancel flow collection when lifecycle state is below the given state param
        // so that the UI doesn't try to update if app goes into the background and Fragment goes
        // to the STOPPED state. Not an issue right now, since there's no background source of data,
        // (besides async PIN/passphrase generation, which shouldn't take long)
        repeatOnLifecycle(state) {
            flow.collect(collector)
        }
    }
}

/** see [com.android.systemui.util.kotlin.combine] */
inline fun <T1, T2, T3, T4, T5, T6, T7, R> combine(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    flow7: Flow<T7>,
    crossinline transform: suspend (T1, T2, T3, T4, T5, T6, T7) -> R
): Flow<R> {
    return kotlinx.coroutines.flow.combine(flow, flow2, flow3, flow4, flow5, flow6, flow7) {
            args: Array<*> ->
        @Suppress("UNCHECKED_CAST")
        transform(
            args[0] as T1,
            args[1] as T2,
            args[2] as T3,
            args[3] as T4,
            args[4] as T5,
            args[5] as T6,
            args[6] as T7
        )
    }
}
