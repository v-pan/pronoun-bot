package bot

import MalformedInputException
import PronounDictionary
import PronounEntry
import bot.commands.AddPronounCommand
import bot.commands.Command
import bot.commands.ScrapeCommand
import bot.commands.TrackCommand
import com.charleskorn.kaml.Yaml
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.createRole
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toSet
import java.io.File

class PronounBot(val kord: Kord) {
    val pronounDictionary = PronounDictionary.fetch()
    private val globalResources = BotResources.fetch() ?: BotResources()

    val commands = mapOf<String, Command>(
        "scrape" to ScrapeCommand(this),
        "track" to TrackCommand(this),
        "add-pronoun" to AddPronounCommand(this)
    )

    val trackedChannels = globalResources.trackedChannels

    suspend fun addRole(member: Member, guild: Guild, pronoun: PronounEntry): Role? {
        val pronounsInUse = PronounDictionary(guild.roles.mapNotNull { PronounEntry.from(it.name) }.toSet())

        val existingPronouns = pronounsInUse.toSet().filter { it.toString().contains(pronoun.toString()) || pronoun.toString().contains(it.toString()) }
        val memberRoles = member.roles.map { it.id }.toSet()
        println("Existing pronouns: $existingPronouns")
        when {
            existingPronouns.size == 1 -> {
                return when {
                    pronoun.toString().contains(existingPronouns.first().toString()) -> {
                        val role = guild.roles.first { it.name == existingPronouns.first().toString() }

                        println("Role: $role")

                        member.edit {
                            roles = memberRoles.plus(role.id).toMutableSet()
                        }

                        role
                    }
                    else -> {
                        TODO("Edit old role, and add new less ambiguous role")
//                        val oldRole = guild.roles.first { it.name == existingPronouns.first().toString() }
//                        val newRole = guild.createRole { name = pronoun.toString() }
//
//                        member.edit {
//                            roles = memberRoles.plus(newRole.id).toMutableSet()
//                        }
                    }
                }
            }
            existingPronouns.isEmpty() -> {
                val role = guild.createRole { name = "${pronoun.subjectPronoun}/${pronoun.objectPronoun}" }

                member.edit {
                    roles = memberRoles.plus(role.id).toMutableSet()
                }

                return role
            }
            else -> throw Exception("Ambiguous pronoun list")
        }
    }

    /*
    Process an introduction template and get the pronouns out

    Currently in the form:

    Name: <name>
    Pronouns: <subject>/<object>, <subject>/<object>
    ...
    */
    fun extractPronounsFrom(message: Message): List<List<PronounEntry>?> {
        val sanityRegex = Regex("[pronounsPRONOUNS]{8}\\b:(?: *\\w+/\\w+,*)+")

        val lines = message.content.split("\n")
        var rawPronouns = lines.first { it.lowercase().contains("pronouns") }
        if (sanityRegex.matches(rawPronouns)) {
            rawPronouns = rawPronouns.split(":").last()
            rawPronouns = rawPronouns.replace(" ", "")
            val subObjPairs = rawPronouns.split(",").map { it.split("/") }

            println("Found pronouns: $subObjPairs")

            return subObjPairs.map { // TODO: Different resolution strategies, better error checking/handling
                pronounDictionary.get(it.first(), it.last()) // TODO: Clarify which variant to choose when ambiguous, should go elsewhere as we do sometimes want to list all variants
            }
        } else {
            throw MalformedInputException("Failed sanity check")
        }
    }

    // TODO: Since these serialize funcs are getting called relatively often, it should probably be optimised at some point
    fun serializeSettings() {
        if (trackedChannels.isNotEmpty()) {
            File("./assets").mkdir()
            val settingsFile = File("./assets/settings.yaml")
            println("Writing settings to ${settingsFile.path}")
            settingsFile.writeText(Yaml.default.encodeToString(BotResources.serializer(), globalResources))
        }
    }

    fun serializeDictionary() {
        File("./assets").mkdir()
        val dictionaryFile = File("./assets/pronounDictionary.yaml")
        println("Writing pronouns to ${dictionaryFile.path}")
        dictionaryFile.writeText(Yaml.default.encodeToString(PronounDictionary.serializer(), pronounDictionary))
    }

    companion object {
        suspend inline operator fun invoke(token: String): PronounBot {
            return PronounBot(Kord(token))
        }
    }
}