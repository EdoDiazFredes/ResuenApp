package cl.lossless.player.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.lossless.player.desktop.DesktopUpdater
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.UpdateState
import cl.lossless.player.desktop.t
import cl.lossless.player.update.AvailableUpdate

/** Aviso en la barra superior cuando hay una versión nueva (o el avance de la descarga). */
@Composable
fun UpdatePill(onInstall: (AvailableUpdate) -> Unit) {
    val (label, update) = when (val s = DesktopUpdater.state) {
        is UpdateState.Available -> t(S.updatePill, s.update.version) to s.update
        is UpdateState.Downloading -> t(S.updateDownloading, (s.progress * 100).toInt()) to null
        is UpdateState.Failed -> (s.update ?: return).let { t(S.updateResumePill, it.version) to it }
        else -> return
    }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.glow(Neon.Accent, 8.dp, shape).clip(shape).background(Neon.Accent)
            .then(if (update != null) Modifier.clickable { onInstall(update) } else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.SystemUpdateAlt, null, tint = Neon.OnAccent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Neon.OnAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Estado y botón de actualización en la tarjeta "Acerca de" de Ajustes. */
@Composable
fun UpdateSettings(onCheck: () -> Unit, onInstall: (AvailableUpdate) -> Unit) {
    val state = DesktopUpdater.state
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val status = when (state) {
            UpdateState.Idle -> null
            UpdateState.Checking -> t(S.updateChecking)
            UpdateState.UpToDate -> t(S.updateUpToDate)
            is UpdateState.Available -> t(S.updateAvailable, state.update.version)
            is UpdateState.Downloading -> t(S.updateDownloading, (state.progress * 100).toInt())
            is UpdateState.Failed ->
                if (state.update != null) t(S.updateDownloadFailed, state.message) else t(S.updateFailed, state.message)
        }
        if (status != null) {
            Text(
                status,
                color = if (state is UpdateState.Available) Neon.Accent else Neon.Muted,
                fontSize = 12.sp,
                fontWeight = if (state is UpdateState.Available) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (state is UpdateState.Available && state.update.notes.isNotBlank()) {
            val shape = RoundedCornerShape(8.dp)
            Text(
                state.update.notes,
                color = Neon.Text,
                fontSize = 12.sp,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().clip(shape).background(Neon.Card).border(1.dp, Neon.Border, shape).padding(10.dp),
            )
        }
        if (state is UpdateState.Downloading) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Neon.Accent,
                trackColor = Neon.Border,
            )
        }
        when (state) {
            is UpdateState.Available -> PrimaryButton(Icons.Rounded.SystemUpdateAlt, t(S.updateInstall)) { onInstall(state.update) }
            is UpdateState.Failed -> {
                val failed = state.update
                if (failed != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(Icons.Rounded.SystemUpdateAlt, t(S.updateResume)) { onInstall(failed) }
                        GhostButton(Icons.Rounded.Refresh, t(S.updateCheck), onCheck)
                    }
                } else {
                    GhostButton(Icons.Rounded.Refresh, t(S.updateCheck), onCheck)
                }
            }
            is UpdateState.Downloading -> GhostButton(Icons.Rounded.Close, t(S.updateCancel)) { DesktopUpdater.cancel() }
            UpdateState.Checking -> Unit
            else -> GhostButton(Icons.Rounded.Refresh, t(S.updateCheck), onCheck)
        }
    }
}
