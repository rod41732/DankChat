package com.flxrs.dankchat.utils.extensions

import com.flxrs.dankchat.chat.ChatItem
import com.flxrs.dankchat.data.twitch.message.ModerationMessage
import com.flxrs.dankchat.data.twitch.message.PrivMessage
import com.flxrs.dankchat.data.twitch.message.SystemMessage
import com.flxrs.dankchat.data.twitch.message.SystemMessageType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun List<ChatItem>.replaceWithTimeouts(moderationMessage: ModerationMessage, scrollBackLength: Int): List<ChatItem> = toMutableList().apply {
    var addClearChat = true
    if (moderationMessage.action == ModerationMessage.Action.Timeout || moderationMessage.action == ModerationMessage.Action.Ban) {
        val end = (lastIndex - 20).coerceAtLeast(0)
        for (idx in lastIndex downTo end) {
            val item = this[idx]
            val message = item.message as? ModerationMessage ?: continue
            if (message.targetUser != moderationMessage.targetUser || message.action != moderationMessage.action) {
                continue
            }

            if ((moderationMessage.timestamp - message.timestamp).milliseconds >= 5.seconds) {
                break
            }

            when {
                !moderationMessage.fromPubsub && message.fromPubsub          -> Unit
                moderationMessage.fromPubsub && !message.fromPubsub          -> this[idx] = item.copy(tag = item.tag + 1, message = moderationMessage)
                moderationMessage.action == ModerationMessage.Action.Timeout -> {
                    val stackedMessage = moderationMessage.copy(stackCount = message.stackCount + 1)
                    this[idx] = item.copy(tag = item.tag + 1, message = stackedMessage)
                }
            }
            addClearChat = false
            break

        }
    }

    if (!moderationMessage.canClearMessages) {
        return addAndLimit(ChatItem(moderationMessage), scrollBackLength)
    }

    for (idx in indices) {
        val item = this[idx]
        when (moderationMessage.action) {
            ModerationMessage.Action.Clear -> {
                this[idx] = when (item.message) {
                    is PrivMessage -> item.copy(tag = item.tag + 1, message = item.message.copy(timedOut = true), isCleared = true)
                    else           -> item.copy(tag = item.tag + 1, isCleared = true)
                }
            }

            ModerationMessage.Action.Timeout,
            ModerationMessage.Action.Ban   -> {
                item.message as? PrivMessage ?: continue
                if (moderationMessage.targetUser != item.message.name) {
                    continue
                }

                this[idx] = item.copy(tag = item.tag + 1, message = item.message.copy(timedOut = true), isCleared = true)
            }

            else                           -> continue
        }
    }

    return when {
        addClearChat -> addAndLimit(ChatItem(moderationMessage), scrollBackLength)
        else         -> this
    }
}

fun List<ChatItem>.replaceWithTimeout(moderationMessage: ModerationMessage, scrollBackLength: Int): List<ChatItem> = toMutableList().apply {
    val targetMsgId = moderationMessage.targetMsgId ?: return@apply
    if (moderationMessage.fromPubsub) {
        val end = (lastIndex - 20).coerceAtLeast(0)
        for (idx in lastIndex downTo end) {
            val item = this[idx]
            val message = item.message as? ModerationMessage ?: continue
            if (message.action == ModerationMessage.Action.Delete && message.targetMsgId == targetMsgId && !message.fromPubsub) {
                this[idx] = item.copy(tag = item.tag + 1, message = moderationMessage)
                return@apply
            }
        }
    }

    for (idx in indices) {
        val item = this[idx]
        if (item.message is PrivMessage && item.message.id == targetMsgId) {
            this[idx] = item.copy(tag = item.tag + 1, message = item.message.copy(timedOut = true), isCleared = true)
            break
        }
    }
    return addAndLimit(ChatItem(moderationMessage), scrollBackLength)
}

fun List<ChatItem>.addAndLimit(item: ChatItem, scrollBackLength: Int): List<ChatItem> = toMutableList().apply {
    add(item)
    while (size > scrollBackLength) {
        removeAt(0)
    }
}

fun List<ChatItem>.addAndLimit(
    items: Collection<ChatItem>,
    scrollBackLength: Int,
    checkForDuplications: Boolean = false
): List<ChatItem> = when {
    checkForDuplications -> plus(items)
        .distinctBy { it.message.id }
        .sortedBy { it.message.timestamp }
        .takeLast(scrollBackLength)

    else                 -> toMutableList().apply {
        addAll(items)
        while (size > scrollBackLength) {
            removeAt(0)
        }
    }
}

fun List<ChatItem>.addSystemMessage(type: SystemMessageType, scrollBackLength: Int): List<ChatItem> {
    return when {
        type != SystemMessageType.Connected -> addAndLimit(ChatItem(SystemMessage(type)), scrollBackLength)
        else                                -> replaceDisconnectedIfNecessary(scrollBackLength)
    }
}

fun List<ChatItem>.replaceDisconnectedIfNecessary(scrollBackLength: Int): List<ChatItem> {
    val item = lastOrNull()
    val message = item?.message
    return when ((message as? SystemMessage)?.type) {
        SystemMessageType.Disconnected -> dropLast(1) + item.copy(message = SystemMessage(SystemMessageType.Reconnected))
        else                           -> addAndLimit(ChatItem(SystemMessage(SystemMessageType.Connected)), scrollBackLength)
    }
}
