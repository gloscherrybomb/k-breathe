package com.tymewear.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/**
 * Wraps KarooSystemService.addConsumer for streaming data types as a Flow.
 * OnStreamState extends KarooEvent and has a .state: StreamState property.
 */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val consumerId = addConsumer<OnStreamState>(
        OnStreamState.StartStreaming(dataTypeId),
        onError = { error ->
            Timber.e("streamDataFlow error for $dataTypeId: $error")
        },
        onComplete = { close() },
        onEvent = { event -> trySend(event.state) },
    )
    awaitClose {
        removeConsumer(consumerId)
    }
}
