package to.sava.legirss.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.sava.legirss.ui.theme.LocalLegirssStyle

private val SCRIM_COLOR = Color(0x33000000)
private val DEFAULT_DIALOG_WIDTH = 440.dp
private val DIALOG_PADDING = 12.dp
private val DIALOG_ITEM_GAP = 8.dp
private val BORDER_WIDTH = 1.dp
private val INPUT_HORIZONTAL_PADDING = 4.dp
private val INPUT_VERTICAL_PADDING = 3.dp
private val BUTTON_HEIGHT = 24.dp
private val BUTTON_MIN_WIDTH = 76.dp
private val BUTTON_PADDING = 10.dp
private val CHECKBOX_SIZE = 13.dp

/**
 * 画面の手前に出して、閉じるまで後ろの画面を操作させないダイアログ。Esc で閉じる。
 *
 * 中に文字の入力欄が無ければ、キーを受けられるようダイアログそのものにフォーカスを置く。
 */
@Composable
fun ModalDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = DEFAULT_DIALOG_WIDTH,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalLegirssStyle.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            // 後ろの画面へクリックを通さない。
            .pointerInput(Unit) { detectTapGestures { } }
            .onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onDismiss() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .border(BORDER_WIDTH, style.colors.border)
                .background(style.colors.panel)
                .focusRequester(focusRequester)
                .focusable()
                .padding(DIALOG_PADDING),
            verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
        ) {
            BasicText(title, style = style.textStyle(bold = true))
            content()
        }
    }
}

/** ダイアログの下端に、ボタンを右寄せで並べる。 */
@Composable
fun DialogButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP, Alignment.End)) { content() }
}

@Composable
fun DialogButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val style = LocalLegirssStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .height(BUTTON_HEIGHT)
            .defaultMinSize(minWidth = BUTTON_MIN_WIDTH)
            .border(BORDER_WIDTH, style.colors.border)
            .background(if (hovered && enabled) style.colors.hover else style.colors.background)
            .hoverable(interactionSource)
            .clickable(interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = BUTTON_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = style.textStyle(color = if (enabled) style.colors.text else style.colors.textDim))
    }
}

/** 見出しの文つきのチェックボックス。文のところを押しても切り替わる。 */
@Composable
fun Checkbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    val style = LocalLegirssStyle.current
    Row(
        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
    ) {
        Canvas(Modifier.size(CHECKBOX_SIZE).border(BORDER_WIDTH, style.colors.border).background(style.colors.background)) {
            if (!checked) return@Canvas
            val unit = size.minDimension / 12f
            val mark = Path().apply {
                moveTo(2.5f * unit, 6f * unit)
                lineTo(5f * unit, 8.5f * unit)
                lineTo(9.5f * unit, 3.5f * unit)
            }
            drawPath(mark, style.colors.text, style = Stroke(width = 1.5f * unit))
        }
        BasicText(label, style = style.textStyle())
    }
}

/** ダイアログの中の説明やメッセージの文。 */
@Composable
fun DialogText(text: String, dim: Boolean = false) {
    val style = LocalLegirssStyle.current
    BasicText(text, style = style.textStyle(color = if (dim) style.colors.textDim else style.colors.text))
}

/**
 * 1 行の文字の入力欄。Enter で [onSubmit] を呼ぶ。
 *
 * @param requestFocus 画面に出たときに、この入力欄へフォーカスを移す。
 */
@Composable
fun TextInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    requestFocus: Boolean = false,
) {
    val style = LocalLegirssStyle.current
    val focusRequester = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = style.textStyle(style.listFontSize, color = if (enabled) style.colors.text else style.colors.textDim),
        cursorBrush = SolidColor(style.colors.text),
        modifier = modifier
            .border(BORDER_WIDTH, style.colors.border)
            .background(style.colors.background)
            .padding(horizontal = INPUT_HORIZONTAL_PADDING, vertical = INPUT_VERTICAL_PADDING)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Enter).also { if (it) onSubmit() }
            },
    )
}
