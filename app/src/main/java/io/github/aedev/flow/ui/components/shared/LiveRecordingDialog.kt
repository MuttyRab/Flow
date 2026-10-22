package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.live.LiveRecordingService
import io.github.aedev.flow.data.video.live.LiveRecordingState

/**
 * Live video is not a finite offline download. Its recording action never
 * submits a request to the normal VOD download queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveRecordingDialog(
    video: Video,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by LiveRecordingService.state.collectAsState()
    val recordingThis = state.busy && state.videoId == video.id
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.live_recording_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.live_recording_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                if (state.busy) {
                    Text(
                        if (recordingThis) {
                            stringResource(R.string.live_recording_progress, state.seconds, state.bytes / (1024 * 1024))
                        } else {
                            stringResource(R.string.live_recording_other)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else if (state.videoId == video.id && state.details.isNotEmpty()) {
                    Text(
                        if (state.status == LiveRecordingState.Status.SAVED) {
                            stringResource(R.string.live_recording_saved, state.details)
                        } else {
                            state.details
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    if (recordingThis) {
                        Button(onClick = {
                            LiveRecordingService.stop(context)
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.live_recording_stop_save))
                        }
                    } else {
                        Button(
                            enabled = !state.busy,
                            onClick = {
                                LiveRecordingService.start(context, video.id, video.title)
                                onDismiss()
                            },
                        ) {
                            Text(stringResource(R.string.live_recording_start))
                        }
                    }
                }
            }
        }
    }
}
