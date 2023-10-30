package net.maizegenetics.phgv2.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

class Phg : CliktCommand() {

    // Need an automated way to get version from build.gradle.kts
    private val version = "2.1.0"

    init {
        versionOption(version)
    }

    override fun run() = Unit

}

fun main(args: Array<String>) = Phg()
    .subcommands(SetupEnvironment(), Initdb(),  CreateRanges(), AgcCompress(), AlignAssemblies(), CreateRefVcf(), CreateMafVcf(), LoadVcf(), ExportHvcf(), CreateFastaFromHvcf())
    .main(args)
