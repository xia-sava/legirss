package to.sava.legirss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.sava.legirss.feed.FeedCandidate
import to.sava.legirss.ui.component.DialogButton
import to.sava.legirss.ui.component.DialogButtons
import to.sava.legirss.ui.component.DialogText
import to.sava.legirss.ui.component.ModalDialog
import to.sava.legirss.ui.component.TextInput
import to.sava.legirss.ui.theme.LocalLegirssStyle

private const val ADD_CHANNEL_TITLE = "チャンネルの追加"
private const val ADD_CHANNEL_PROMPT = "フィードの URL か、フィードを案内しているページの URL"
private const val ADD_LABEL = "追加"
private const val CHECKING_LABEL = "確認中…"
private const val CANCEL_LABEL = "キャンセル"

private val CANDIDATE_PADDING = 4.dp

/** 手前に出す窓を、[dialog] の種類に合わせて描く。 */
@Composable
fun DialogHost(dialog: DialogState, actions: MainActions) {
    when (dialog) {
        is DialogState.AddChannel -> AddChannelDialog(dialog, actions)
    }
}

@Composable
private fun AddChannelDialog(dialog: DialogState.AddChannel, actions: MainActions) {
    var url by remember { mutableStateOf(TextFieldValue()) }
    ModalDialog(ADD_CHANNEL_TITLE, onDismiss = actions::closeDialog) {
        DialogText(ADD_CHANNEL_PROMPT)
        TextInput(
            value = url,
            onValueChange = { url = it },
            onSubmit = { actions.submitAddChannel(url.text) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !dialog.busy,
            requestFocus = true,
        )
        dialog.message?.let { DialogText(it) }
        if (dialog.candidates.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                dialog.candidates.forEach { candidate ->
                    CandidateRow(candidate, enabled = !dialog.busy) { actions.submitAddChannel(candidate.url) }
                }
            }
        }
        DialogButtons {
            DialogButton(
                label = if (dialog.busy) CHECKING_LABEL else ADD_LABEL,
                onClick = { actions.submitAddChannel(url.text) },
                enabled = !dialog.busy,
            )
            DialogButton(CANCEL_LABEL, onClick = actions::closeDialog)
        }
    }
}

@Composable
private fun CandidateRow(candidate: FeedCandidate, enabled: Boolean, onClick: () -> Unit) {
    val style = LocalLegirssStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (hovered && enabled) style.colors.selection else style.colors.background)
            .hoverable(interactionSource)
            .clickable(interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(CANDIDATE_PADDING),
    ) {
        candidate.title?.let { BasicText(it, style = style.textStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        BasicText(candidate.url, style = style.textStyle(color = style.colors.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
