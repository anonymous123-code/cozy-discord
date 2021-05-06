package org.quiltmc.community.extensions.messagelog

import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.utils.deltas.MessageDelta
import com.kotlindiscord.kord.extensions.utils.getUrl
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.Optional
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.channel.Category
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.guild.GuildCreateEvent
import dev.kord.core.event.message.MessageBulkDeleteEvent
import dev.kord.core.event.message.MessageDeleteEvent
import dev.kord.core.event.message.MessageUpdateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.quiltmc.community.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val LINE_LENGTH = 45

private const val SINGLE_MESSAGE_LIMIT = 1900
private const val DUAL_MESSAGE_LIMIT = 950

@Suppress("StringLiteralDuplication", "MagicNumber")
class MessageLogExtension(bot: ExtensibleBot) : Extension(bot) {
    override val name = "message-log"

    private val logger = KotlinLogging.logger { }

    private var loopJob: Job? = null
    private lateinit var messageChannel: Channel<LogMessage>

    private val rotators: MutableMap<Snowflake, CategoryRotator> = mutableMapOf()

    private val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(bot.settings.i18nBuilder.defaultLocale)
        .withZone(ZoneId.of("UTC"))

    private val jsonFormat = Json { prettyPrint = true }

    override suspend fun setup() {
        messageChannel = Channel(50)

        event<GuildCreateEvent> {
            check(inQuiltGuild)

            action {
                val category = MESSAGE_LOG_CATEGORIES.mapNotNull {
                    try {
                        event.guild.getChannelOrNull(it) as? Category
                    } catch (e: IllegalArgumentException) {
                        null  // Channel isn't on this guild
                    }
                }.firstOrNull()

                if (category == null) {
                    logger.warn {
                        "No message log category found for guild: ${event.guild.name} (${event.guild.id.value})"
                    }

                    return@action
                }

                val modLogChannel = event.guild.channels.firstOrNull { it.name == "moderation-log" }
                    ?.asChannelOrNull() as? GuildMessageChannel

                if (modLogChannel == null) {
                    logger.warn {
                        "No moderation-log channel found for guild: ${event.guild.name} (${event.guild.id.value})"
                    }

                    return@action
                }

                val rotator = CategoryRotator(category, modLogChannel)
                rotators[category.guildId] = rotator

                rotator.start()
            }
        }

        event<MessageBulkDeleteEvent> {
            check(inQuiltGuild)

            action {
                var messages = "# Deleted Messages (${event.messages.count()}\n\n"

                if (event.messages.isEmpty()) {
                    messages += "**No messages were cached.**"
                } else {
                    event.messages.forEach {
                        messages += "**ID:** ${it.id.value}\n\n"

                        if (it.author != null) {
                            messages += "**Author:** ${it.author!!.tag}\n"
                            messages += "**Author ID:** ${it.author!!.id.value}\n\n"
                        } else {
                            messages += "**Display Name:** ${it.data.author.username}\n"
                            messages += "**Webhook ID:** ${it.data.webhookId.value ?: "N/A"}\n\n"
                        }

                        messages += "**Sent:** ${dateTimeFormatter.format(it.timestamp)} (UTC)\n"

                        if (it.editedTimestamp != null) {
                            messages += "**Last Edited:** ${dateTimeFormatter.format(it.editedTimestamp)} (UTC)\n"
                        }

                        messages += "\n"

                        if (it.content.isNotEmpty()) {
                            messages += "## Content\n\n"

                            messages += splitContent(it.content)

                            messages += "\n\n"
                        }

                        if (it.reactions.isNotEmpty()) {
                            messages += "## Reactions\n\n"

                            it.reactions.sortedBy { r -> r.count }.forEach { r ->
                                val emoji = r.emoji

                                messages += "${r.count.toString().padStart(5, ' ')} | ${emoji.name}"

                                if (emoji is ReactionEmoji.Custom) {
                                    messages += " (`${emoji.id.value}`)"
                                }

                                messages += "\n"
                            }

                            messages += "\n"
                        }

                        if (it.attachments.isNotEmpty()) {
                            messages += "## Attachments\n\n"

                            it.attachments.forEach { a ->
                                messages += "**Filename:** ${a.filename}\n"
                                messages += "**Size (Bytes):** ${a.size}\n\n"

                                messages += "**URL:** ${a.url}\n"
                                messages += "**Proxy URL:** ${a.proxyUrl}\n\n"

                                messages += "**Image:** ${a.isImage}\n"
                                messages += "**Spoiler:** ${a.isSpoiler}\n\n"
                            }
                        }

                        if (it.embeds.isNotEmpty()) {
                            messages += "## Embeds\n\n"

                            it.embeds.forEach { e ->
                                messages += "```json\n${jsonFormat.encodeToString(e.data)}\n```\n\n"
                            }
                        }

                        messages += "---\n\n"
                    }

                    messages += "No further messages were cached."
                }

                send(
                    LogMessage(event.guild!!.asGuild()) {
                        allowedMentions { }

                        addFile("messages.md", messages.byteInputStream())
                    }
                )

                send(
                    LogMessage(event.guild!!.asGuild()) {
                        allowedMentions { }

                        embed {
                            color = COLOUR_NEGATIVE
                            title = "Bulk message delete"

                            timestamp = Instant.now()

                            field {
                                name = "Channel"
                                value = event.channel.mention
                                inline = true
                            }

                            field {
                                name = "Count"
                                value = event.messageIds.size.toString()
                                inline = true
                            }
                        }
                    }
                )
            }
        }

        event<MessageDeleteEvent> {
            check(inQuiltGuild)

            action {
                val message = event.message
                val messageContent = message?.content

                send(
                    LogMessage(event.guild!!.asGuild()) {
                        allowedMentions { }

                        if (messageContent != null && messageContent.length <= SINGLE_MESSAGE_LIMIT) {
                            content = "**Message Content**\n\n" +

                                    messageContent
                        } else if (messageContent == null) {
                            content = "_Message was not cached, so further information is not available._"
                        }

                        embed {
                            color = COLOUR_NEGATIVE
                            title = "Message deleted"

                            timestamp = Instant.now()

                            if (message != null) {
                                addMessage(message)
                            } else {
                                field {
                                    name = "Channel"
                                    value = event.channel.mention
                                    inline = true
                                }

                                field {
                                    name = "Created"
                                    value = "${dateTimeFormatter.format(event.messageId.timeStamp)} (UTC)\n"
                                    inline = true
                                }
                            }
                        }
                    }
                )

                if (messageContent != null && messageContent.length > SINGLE_MESSAGE_LIMIT) {
                    send(
                        LogMessage(event.guild!!.asGuild()) {
                            allowedMentions { }

                            addFile("old.md", splitContent(message.content).byteInputStream())
                        }
                    )
                }
            }
        }

        event<MessageUpdateEvent> {
            check(inQuiltGuild)

            action {
                val old = event.old
                val new = event.getMessage()

                val delta = MessageDelta.from(old, new)

                if (delta != null && delta.content is Optional.Missing) {
                    return@action  // Message content wasn't edited and we don't care about embeds/reactions/etc
                }

                val canEmbedOld = old?.content?.length ?: DUAL_MESSAGE_LIMIT + 1 <= DUAL_MESSAGE_LIMIT
                val canEmbedNew = new.content.length <= DUAL_MESSAGE_LIMIT

                send(
                    LogMessage(new.getGuild()) {
                        allowedMentions { }

                        content = ""

                        if (old != null && canEmbedOld) {
                            content = "**Old Message Content**\n\n" +

                                    old.content
                        }

                        if (canEmbedNew) {
                            if (content!!.isNotEmpty()) {
                                content += "\n\n"
                            }

                            content += "**New Message Content**\n\n" +

                                    new.content
                        }

                        embed {
                            color = COLOUR_BLURPLE
                            title = "Message edited"

                            timestamp = Instant.now()

                            addMessage(new)

                            if (delta == null) {
                                description = "**Note:** Message was not cached, so the content may not have been " +
                                        "edited."
                            }
                        }
                    }
                )

                @Suppress("UnnecessaryParentheses")  // some of us are insecure :>
                if ((old != null && !canEmbedOld) || !canEmbedNew) {
                    send(
                        LogMessage(new.getGuild()) {
                            allowedMentions { }

                            if (old != null && !canEmbedOld) {
                                addFile("old.md", splitContent(old.content).byteInputStream())
                            }

                            if (!canEmbedNew) {
                                addFile("new.md", splitContent(new.content).byteInputStream())
                            }
                        }
                    )
                }
            }
        }

        start()
    }

    override suspend fun doUnload() {
        super.doUnload()

        stop()
        rotators.clear()
    }

    private fun start() {
        loopJob = bot.kord.launch { sendLoop() }
    }

    private fun stop() {
        loopJob?.cancel()
        messageChannel.close()

        rotators.values.forEach { it.stop() }
    }

    suspend fun send(message: LogMessage) = messageChannel.send(message)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun sendLoop() {
        for (logMessage in messageChannel) {
            val rotator = rotators[logMessage.guild.id]

            if (rotator == null) {
                logger.warn {
                    "No category rotator found for guild: ${logMessage.guild.name} (${logMessage.guild.id.value})"
                }

                continue
            }

            rotator.send(logMessage.messageBuilder)
        }
    }

    private fun splitContent(content: String) = content.split("\n").joinToString("\n") {
        if (it.length > LINE_LENGTH) it.chunkByWhitespace(LINE_LENGTH).joinToString("\n") else it
    }

    private suspend fun EmbedBuilder.addMessage(message: Message) {
        val author = message.author

        footer {
            text = message.id.asString
        }

        field {
            name = "URL"
            value = message.getUrl()
        }

        if (author != null) {
            field {
                name = "Author Mention"
                value = author.mention
                inline = true
            }

            field {
                name = "Author ID/Tag"
                value = "`${author.id.value}` / `${author.tag}`"
                inline = true
            }
        } else {
            field {
                name = "Message Username"
                value = message.data.author.username
                inline = true
            }

            field {
                name = "Webhook ID"
                value = message.webhookId?.asString ?: "N/A"
                inline = true
            }
        }

        field {
            name = "Channel"
            value = message.channel.mention
            inline = true
        }

        field {
            name = "Sent"
            value = "${dateTimeFormatter.format(message.timestamp)} (UTC)\n"
            inline = true
        }

        if (message.editedTimestamp != null) {
            field {
                name = "Last Edited"
                value = "${dateTimeFormatter.format(message.editedTimestamp)} (UTC)\n"
                inline = true
            }
        }

        if (message.attachments.isNotEmpty()) {
            field {
                name = "Attachments"
                value = message.attachments.size.toString()
                inline = true
            }
        }

        if (message.embeds.isNotEmpty()) {
            field {
                name = "Embeds"
                value = message.embeds.size.toString()
                inline = true
            }
        }

        if (message.reactions.isNotEmpty()) {
            field {
                name = "Embeds"
                value = message.reactions.sumBy { reaction -> reaction.count }.toString()
                inline = true
            }
        }
    }
}
