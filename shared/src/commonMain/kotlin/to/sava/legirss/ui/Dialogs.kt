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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.sava.legirss.feed.FeedCandidate
import to.sava.legirss.model.NodeKind
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
private const val OK_LABEL = "OK"

private const val ADD_GROUP_TITLE = "グループの追加"
private const val RENAME_TITLE = "名前の変更"
private const val CHANGE_FEED_URL_TITLE = "フィードの URL の変更"

private const val DELETE_TITLE = "削除"
private const val DELETE_LABEL = "削除"
private const val DELETE_GROUP_CONSEQUENCE = "中のグループとチャンネル、その記事も全て消えます。"
private const val DELETE_CHANNEL_CONSEQUENCE = "このチャンネルの記事も全て消えます。"

private val CANDIDATE_PADDING = 4.dp

/** 手前に出す窓を、[dialog] の種類に合わせて描く。 */
@Composable
fun DialogHost(dialog: DialogState, actions: MainActions) {
    when (dialog) {
        is DialogState.AddChannel -> AddChannelDialog(dialog, actions)
        is DialogState.EditText -> EditTextDialog(dialog, actions)
        is DialogState.ConfirmDelete -> ConfirmDeleteDialog(dialog, actions)
    }
}

@Composable
private fun EditTextDialog(dialog: DialogState.EditText, actions: MainActions) {
    // 開いたときは全体を選んでおき、そのまま打てば置き換わるようにする。
    var text by remember { mutableStateOf(TextFieldValue(dialog.initialText, TextRange(0, dialog.initialText.length))) }
    val title = when (dialog.target) {
        EditTarget.NewGroup -> ADD_GROUP_TITLE
        is EditTarget.NodeName -> RENAME_TITLE
        is EditTarget.FeedUrl -> CHANGE_FEED_URL_TITLE
    }
    ModalDialog(title, onDismiss = actions::closeDialog) {
        TextInput(
            value = text,
            onValueChange = { text = it },
            onSubmit = { actions.submitText(text.text) },
            modifier = Modifier.fillMaxWidth(),
            requestFocus = true,
        )
        DialogButtons {
            DialogButton(OK_LABEL, onClick = { actions.submitText(text.text) })
            DialogButton(CANCEL_LABEL, onClick = actions::closeDialog)
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(dialog: DialogState.ConfirmDelete, actions: MainActions) {
    val consequence = when (dialog.kind) {
        NodeKind.GROUP -> DELETE_GROUP_CONSEQUENCE
        NodeKind.CHANNEL -> DELETE_CHANNEL_CONSEQUENCE
    }
    ModalDialog(
        title = DELETE_TITLE,
        onDismiss = actions::closeDialog,
        modifier = Modifier.onPreviewKeyEvent { event ->
            (event.type == KeyEventType.KeyDown && event.key == Key.Enter).also { if (it) actions.confirmDelete() }
        },
    ) {
        DialogText("「${dialog.name}」を削除します。$consequence")
        DialogButtons {
            DialogButton(DELETE_LABEL, onClick = actions::confirmDelete)
            DialogButton(CANCEL_LABEL, onClick = actions::closeDialog)
        }
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
