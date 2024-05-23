package net.maizegenetics.phgv2.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

/**
 * This class provides a means of preparing data for aligning assemblies in a slurm data array job.
 * It  takes as input a list of assemblies, a reference genome, a reference gff file and the reference cds fasta
 * aligned sam file, created by the AlignAssemblies class.  The latter should have been created via
 * align-assemblies with the just-ref-prep option.
 *
 * What is needed beyond this is taking the slurm command file and verifying it can be run in a slurm job.
 * That will not be done in a junit test - needs to happen on scinet.
 */

class PrepareSlurmAlignFile: CliktCommand(help = "create files for aligning assemblies in a slurm data array job") {
    val gff by option(help = "Full path to the reference gff file")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--gff must not be blank"
            }
        }

    val referenceFile by option(help = "Full path to reference file")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--reference-file must not be blank"
            }
        }

    val referenceCdsSam by option(help = "Full path to reference SAM file created by align-assemblies command when the just-ref-prep option is used." +
    "This is the SAM file created when aligning the reference fasta to the reference CDS regions.")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--reference-cds-sam must not be blank"
            }
        }

    val referenceCdsFasta by option(help = "Full path to reference fasta file created by AlignAssemblies class when aligning to CDS regions")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--reference-cds-fasta must not be blank"
            }
        }


    // THis is a list of assemblies vs the option of a single file because the output
    // is a text file that contains a line for each assembly to be aligned.
    val assemblies by option(
        "-a",
        "--assemblies",
        help = "File containing list of assemblies to align, 1 per line, full path to updated file created via the phg prepare-assemblies command."
    )
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--assemblies must not be blank"
            }
        }

    val slurmCommandFile by option( help = "Full path and name for a file where the individual align-assembly commands will be printed")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--slurm-command-file must not be blank"
            }
        }

    val outputDir by option("-o", "--output-dir", help = "Directory where temporary and final files from anchorwave alignment will be written")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--output-dir must not be blank"
            }
        }

    val totalThreads by option(help = "Number of threads available.  These will be split among the alignments that are run in parallel")
        .int()
        .default(1)

    val refMaxAlignCov by option(help = "Anchorwave proali parameter R, indicating reference genome maximum alignment coverage.")
        .int()
        .default(1)

    val queryMaxAlignCov by option(help = "Anchorwave proali parameter Q, indicating query genome maximum alignment coverage.")
        .int()
        .default(1)

    override fun run() {
        // This method creates the files needed for aligning assemblies in a slurm data array job.
        // For each assembly in the list, add an entry to the slurm data array job file that calls align-assemblies clikt command
        // THis is implemented in the createSlrumDatArrayJobFile() function
        println("PrepareSlurmALignFile: in run, calling createSlurmDataArrayJobFile")
        createSlurmDataArrayJobFile(
            gff,
            referenceFile,
            referenceCdsSam,
            referenceCdsFasta,
            assemblies,
            slurmCommandFile,
            outputDir,
            totalThreads,
            1, // default for in-paralel as there is only one assembly per line
            refMaxAlignCov,
            queryMaxAlignCov
        )
    }

    // This method creates the files needed for aligning assemblies in a slurm data array job.
    // it creates a single file, where there is a line for each assembly to be aligned.
    // In each of these lines, a call is made to the phg align-assemblies command using the parameters supplied here
    // The output of this method is a file that can be submitted to slurm as a data array job.
    //The input to the function is all the parameters that are defined above.
    //The output is a file that can be submitted to slurm as a data array job.
    //The file will contain a line for each assembly to be aligned.  Each line will be a call to the phg align-assemblies command.
    //The parameters for the call will be the parameters supplied to this function.
    fun createSlurmDataArrayJobFile(
        gff: String,
        referenceFile: String,
        referenceCdsSam: String,
        referenceCdsFasta: String,
        assemblies: String,
        slurmCommandFile: String,
        outputDir:String,
        totalThreads: Int,
        inParallel: Int,
        refMaxAlignCov: Int,
        queryMaxAlignCov: Int
    ) {
        // This method creates the file needed for aligning assemblies in a slurm data array job.
        // For each assembly in the list, add an entry to the slurm data array job file that calls align-assemblies clikt command

        // Create a list of assemblies
        val assembliesList = File(assemblies).readLines().filter { it.isNotBlank() }
        val writer = File(slurmCommandFile).bufferedWriter()
        // for each assembly in the list, write a line to the file that calls the align-assemblies command
        // We use the parameters supplied to this function as the parameters for the align-assemblies command,
        assembliesList.forEach {
            // THis might not be correct - check copilots' parameters.
            // TODO: Should I even have in-parallel and total-threads as parameters?
            writer.write("phg align-assemblies --gff $gff --output-dir $outputDir --reference-file $referenceFile --reference-sam $referenceCdsSam --reference-cds-fasta $referenceCdsFasta --assembly-file $it --total-threads $totalThreads --in-parallel $inParallel --ref-max-align-cov $refMaxAlignCov --query-max-align-cov $queryMaxAlignCov\n")
        }
        writer.close()
    }
}
