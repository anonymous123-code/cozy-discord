/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package org.quiltmc.community

import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.modules.extra.mappings.extMappings
import com.kotlindiscord.kord.extensions.utils.env
import com.kotlindiscord.kord.extensions.utils.loadModule
import dev.kord.common.entity.Snowflake
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
import me.shedaniel.linkie.namespaces.YarnNamespace
import org.koin.dsl.bind
import org.quiltmc.community.extensions.SyncExtension
import org.quiltmc.community.extensions.messagelog.MessageLogExtension
import org.quiltmc.community.extensions.minecraft.MinecraftExtension
import org.quiltmc.community.extensions.suggestions.JsonSuggestions
import org.quiltmc.community.extensions.suggestions.SuggestionsData
import org.quiltmc.community.extensions.suggestions.SuggestionsExtension

@Suppress("MagicNumber", "UnderscoresInNumericLiterals")
private val NON_YARN_CHANNEL = Snowflake(
    env("NON_YARN_CHANNEL_ID")?.toLong() ?: 856825412695883796
)

@OptIn(PrivilegedIntent::class)
suspend fun main() {
    val bot = ExtensibleBot(TOKEN) {
        intents {
            +Intents.all
        }

        members {
            all()
        }

        messageCommands {
            defaultPrefix = "?"

            check {
                if (event.message.author == null) {
                    fail()
                }
            }
        }

        slashCommands {
            enabled = true
        }

        extensions {
            add(::MessageLogExtension)
            add(::MinecraftExtension)
            add(::SuggestionsExtension)
            add(::SyncExtension)

            sentry {
                enable = false
            }

            extMappings {
                namespaceCheck { namespace ->
                    {
                        failIfNot("Non-Yarn commands may only be run in <#${NON_YARN_CHANNEL.value}>") {
                            namespace == YarnNamespace &&
                                    (event.message.getGuildOrNull() == null ||
                                            event.message.channelId == NON_YARN_CHANNEL)
                        }
                    }
                }
            }
        }

        hooks {
            afterKoinSetup {
                val suggestions = JsonSuggestions()
                suggestions.load()

                loadModule { single { suggestions } bind SuggestionsData::class }
            }
        }
    }

    bot.start()
}
