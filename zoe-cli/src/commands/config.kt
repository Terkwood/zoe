// Copyright (c) 2020 Adevinta.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.adevinta.oss.zoe.cli.commands

import com.adevinta.oss.zoe.cli.config.*
import com.adevinta.oss.zoe.cli.utils.globalTermColors
import com.adevinta.oss.zoe.cli.utils.yaml
import com.adevinta.oss.zoe.core.utils.buildJson
import com.adevinta.oss.zoe.core.utils.json
import com.adevinta.oss.zoe.core.utils.logger
import com.adevinta.oss.zoe.core.utils.toJsonNode
import com.adevinta.oss.zoe.service.utils.userError
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ConfigCommand : CliktCommand(name = "config", help = "Initialize zoe") {
    override fun run() {}
}

@ExperimentalCoroutinesApi
@FlowPreview
class ConfigInit : CliktCommand(
    name = "init",
    help = "Initialize zoe config",
    epilog = with(globalTermColors) {
        """```
        |Examples:
        |
        |  Init config with a default configuration file:
        |  > ${bold("zoe config init")}
        |
        |  Load config from a local directory:
        |  > ${bold("""zoe config init --from local --path /path/to/existing/config""")}
        |
        |  Load config from a git repository:
        |  > ${bold("""zoe config init --from git --url 'https://github.com/adevinta/zoe.git' --dir tutorials/simple/config""")}
        |
        |  Load config from a git repository with authentication:
        |  > ${bold("""zoe config init --from git --url 'https://github.company.com/example/config.git' --dir zoe-config --username user --password pass""")}
        |
        |  You can also use a github token as a username:
        |  > ${bold("""zoe config init --from git --url 'https://github.company.com/example/config.git' --dir zoe-config --username gh-token""")}
        |
        |```""".trimMargin()
    }
), KoinComponent {

    private val ctx by inject<CliContext>()

    private val recreate: Boolean by option("--recreate", help = "Recreate the configuration folder from scratch").flag(
        default = false
    )

    private val overwrite: Boolean by option("--overwrite", help = "Overwrite existing configuration folder").flag(
        default = false
    )

    private val from by option("--from", help = "Import from an existing configuration folder").groupChoice(
        "local" to LoadFrom.Local(),
        "git" to LoadFrom.Git()
    )

    override fun run() {
        val configDir = ctx.configDir
        val fromDir = from?.getSourceDir()

        if (recreate && configDir.exists()) {
            logger.info("deleting existing config directory : ${configDir.absolutePath}")
            configDir.deleteRecursively()
        }

        Files.createDirectories(configDir.toPath())

        when {

            fromDir != null -> {
                val sourceConfigFiles = fromDir.listFiles { file -> file.isFile && file.extension == "yml" }
                    ?: userError("provided source is not listable : $fromDir")

                for (file in sourceConfigFiles) {
                    val name = file.name
                    val source = file.toPath()
                    val target = configDir.toPath().resolve(name)
                    val options = if (overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()

                    try {
                        logger.info("copying '$source' to '$target'")
                        Files.copy(file.toPath(), target, *options)
                    } catch (exists: FileAlreadyExistsException) {
                        userError("file already exists : ${exists.message} (use --overwrite)")
                    }
                }
            }

            else -> {
                logger.info("creating a new config file...")

                val target = ctx.configDir.toPath().resolve("default.yml").toFile()

                if (target.exists() && !overwrite) {
                    logger.info("config file '${target.absolutePath}' already exists ! (--overwrite to recreate)")
                    return
                }

                val config = EnvConfig(
                    clusters = mapOf(
                        "local" to ClusterConfig(
                            props = mapOf(
                                "bootstrap.servers" to "localhost:29092",
                                "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                                "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                                "value.serializer" to "org.apache.kafka.common.serialization.ByteArraySerializer"
                            ),
                            topics = mapOf("input" to TopicConfig("input-topic", null)),
                            registry = null
                        )
                    ),
                    runners = RunnersSection(default = RunnerName.Local),
                    storage = null,
                    secrets = null
                )

                val jsonValue: JsonNode = json.valueToTree(config)

                yaml.setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(target, jsonValue)
            }
        }
    }
}

class ConfigClusters : CliktCommand(name = "clusters") {
    override fun run() {}
}

class ClustersList : CliktCommand(name = "list", help = "List configured clusters"), KoinComponent {
    private val ctx by inject<CliContext>()
    private val env by inject<EnvConfig>()

    override fun run() {
        val response = env.clusters.map { (name, config) ->
            mapOf(
                "cluster" to name,
                "brokers" to config.props["bootstrap.servers"],
                "registry" to config.registry,
                "topics" to config.topics.map { (alias, topic) ->
                    buildJson {
                        put("alias", alias)
                        put("name", topic.name)
                    }
                },
                "groups" to config.groups
            )
        }

        ctx.term.output.format(response.toJsonNode()) { echo(it) }
    }
}

class ConfigEnvironments : CliktCommand(name = "environments"), KoinComponent {
    override fun run() {}
}

class EnvironmentsList : CliktCommand(name = "list"), KoinComponent {
    private val ctx by inject<CliContext>()

    override fun run() {
        val envs = ctx.configDir
            .takeIf { it.exists() && it.isDirectory }
            ?.listFiles()
            ?.map { it.nameWithoutExtension }
            ?.filter { it != "common" }
            ?: emptyList()

        ctx.term.output.format(envs.toJsonNode()) { echo(it) }
    }

}


sealed class LoadFrom(name: String) : OptionGroup(name) {
    class Local : LoadFrom("Options to load from local") {
        val path: File
            by option("--path")
                .file(mustExist = true, canBeDir = true, mustBeReadable = true)
                .required()
    }

    class Git : LoadFrom("Options to load from git") {
        val url: String by option("--url", help = "remote url of the repository").required()
        val dir: String by option("--dir", help = "path to the config inside the repo").default(".")
        val username: String? by option("-u", "--username")
        val password: String? by option("--password")
    }
}

fun LoadFrom.getSourceDir(): File = when (this) {
    is LoadFrom.Local -> path

    is LoadFrom.Git -> {
        val temp = Files.createTempDirectory("tmp-zoe-config-init").toFile().also { it.deleteOnExit() }

        Git
            .cloneRepository()
            .setURI(url)
            .let {
                if (password != null || username != null) it.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(username ?: "", password ?: "")
                ) else {
                    it
                }
            }
            .setDirectory(temp)
            .call()

        temp.resolve(dir)
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
fun configCommands() = ConfigCommand().subcommands(
    ConfigInit(),
    ConfigClusters().subcommands(ClustersList()),
    ConfigEnvironments().subcommands(EnvironmentsList())
)
