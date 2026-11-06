package io.github.denhaiz.exmail.ui.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.denhaiz.exmail.data.SwipeAction
import io.github.denhaiz.exmail.data.db.MessageEntity

/** Порядок в списке выбора (настройки). */
val SWIPE_CHOICES = listOf(
    SwipeAction.NONE,
    SwipeAction.READ,
    SwipeAction.FLAG,
    SwipeAction.REPLY,
    SwipeAction.FORWARD,
    SwipeAction.MOVE,
    SwipeAction.DELETE,
)

/** Название в настройках. */
val SwipeAction.title: String
    get() = when (this) {
        SwipeAction.NONE -> "Ничего"
        SwipeAction.READ -> "Прочитано / непрочитано"
        SwipeAction.FLAG -> "Флажок"
        SwipeAction.REPLY -> "Ответить"
        SwipeAction.FORWARD -> "Переслать"
        SwipeAction.MOVE -> "Переместить в папку"
        SwipeAction.DELETE -> "Удалить"
    }

/** Подпись под строкой при свайпе — то, что произойдёт с этим письмом. */
fun SwipeAction.label(m: MessageEntity): String = when (this) {
    SwipeAction.READ -> if (m.isRead) "Непрочитано" else "Прочитано"
    SwipeAction.FLAG -> if (m.flagged) "Снять флажок" else "Флажок"
    SwipeAction.MOVE -> "Переместить"
    else -> title
}

fun SwipeAction.icon(m: MessageEntity? = null): ImageVector = when (this) {
    SwipeAction.NONE -> Icons.Outlined.Block
    SwipeAction.READ -> if (m?.isRead == false) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread
    SwipeAction.FLAG -> Icons.Outlined.Flag
    SwipeAction.REPLY -> Icons.AutoMirrored.Outlined.Reply
    SwipeAction.FORWARD -> Icons.AutoMirrored.Outlined.Forward
    SwipeAction.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    SwipeAction.DELETE -> Icons.Outlined.Delete
}
