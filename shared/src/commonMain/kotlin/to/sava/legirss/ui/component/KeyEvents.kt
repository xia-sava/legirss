package to.sava.legirss.ui.component

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

/** Enter のキーか。文字キーの側の Enter と、テンキーの Enter のどちらも Enter として扱う。 */
val KeyEvent.isEnterKey: Boolean get() = key == Key.Enter || key == Key.NumPadEnter
