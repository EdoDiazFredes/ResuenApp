package cl.lossless.player.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cl.lossless.player.R

/** Al abrir la app busca una versión nueva y, si la hay, la ofrece una vez por sesión. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppUpdater.check(context.applicationContext) }
    val available = AppUpdater.state as? UpdateState.Available ?: return
    if (AppUpdater.promptDismissed) return
    AlertDialog(
        onDismissRequest = { AppUpdater.promptDismissed = true },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = { Text(stringResource(R.string.update_dialog_body, available.update.version)) },
        confirmButton = {
            TextButton(onClick = {
                AppUpdater.promptDismissed = true
                AppUpdater.install(context, available.update)
            }) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = {
            TextButton(onClick = { AppUpdater.promptDismissed = true }) { Text(stringResource(R.string.update_later)) }
        },
    )
}
