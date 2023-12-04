package net.maizegenetics.phgv2.agc

import com.github.ajalt.clikt.testing.test
import net.maizegenetics.phgv2.cli.TestExtension
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotateFastasTest {
    companion object {

        val tempDir = "${System.getProperty("user.home")}/temp/phgv2Tests/tempDir/"
        @JvmStatic
        @BeforeAll
        fun setup() {
            File(TestExtension.testInputFastaDir).mkdirs()
            File(TestExtension.testOutputFastaDir).mkdirs()

            val fastaOrigDir = "data/test/smallseq"
            val fastaInputDir = TestExtension.testInputFastaDir
            // copy files with extension .fa from data/test/smallseq to fastaInputDir for this test
            val fastaFiles = File(fastaOrigDir).listFiles { file -> file.extension == "fa" }
            fastaFiles.forEach { file -> file.copyTo(File(fastaInputDir, file.name)) }

        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            File(TestExtension.tempDir).deleteRecursively()
        }
    }

    @Test
    fun testCliktParams() {
        val annotateFastas = AnnotateFastas()

        // Test missing fasta-list parameter
        val resultMissingKeyfile = annotateFastas.test(" --output-dir ${TestExtension.testOutputFastaDir}")
        assertEquals(resultMissingKeyfile.statusCode, 1)
        assertEquals("Usage: annotate-fastas [<options>]\n" +
                "\n" +
                "Error: invalid value for --keyfile: --keyfile must not be blank\n",resultMissingKeyfile.output)

        // Test missing output-dir parameter
        val resultMissingOutDir = annotateFastas.test("--keyfile ${TestExtension.testInputFastaDir} ")
        assertEquals(resultMissingOutDir.statusCode, 1)
        assertEquals("Usage: annotate-fastas [<options>]\n" +
                "\n" +
                "Error: invalid value for --output-dir: --output-dir must not be blank\n",resultMissingOutDir.output)

    }

    @Test
    fun testAnnotateFastaCommand() {
        val fastaInputDir = TestExtension.testInputFastaDir
        val fastaOutputDir = TestExtension.testOutputFastaDir

        // Create a List<String> of fasta files in the fastaInputDir
        val fileList = File(fastaInputDir).listFiles().filter { it.extension == "fa" || it.extension == "fasta" }.map { it.absolutePath }

        // Create a tab-delimited file of fasta file names and sample names
        // The fasta file names are the full path names for the fasta files in the fastaInputDir
        // the sample names are the fasta file names minus the extension
        // write the fasta file names in the first column and the sample names in the second column of a tab-delimited file
        // named ${fastaOutputDir}/fastaCreateFileNames.txt
        val filesToUpdate = File(fastaOutputDir, "fastaCreateFileNames.txt")
        filesToUpdate.writeText(fileList.joinToString("\n") { "${it}\t${File(it).nameWithoutExtension}" })

        // Test the AnnotateFasta class
        val annotateFastas = AnnotateFastas()
        val result = annotateFastas.test( "--keyfile ${filesToUpdate} --threads 2 --output-dir ${TestExtension.testOutputFastaDir}")
        assertEquals(result.statusCode, 0)

        // get a list of fasta files created in the fastaOutputDir, as a List<String>
        val updatedFiles = File(fastaOutputDir).listFiles().filter { it.extension == "fa" || it.extension == "fasta" }.map { it.absolutePath }

        // verify the idlines of each fasta file were updated to include
        // "sampleName=${sampleName}" where sampleName is the fasta file name minus the extension
        updatedFiles.forEach { fastaFile ->
            val sampleName = File(fastaFile).nameWithoutExtension
            val newFilename = "${fastaOutputDir}/${File(fastaFile).name}"
            File(newFilename).forEachLine { line ->
                if (line.startsWith(">")) {
                    assertTrue(line.contains("sampleName=${sampleName}"))
                }
            }
        }

        // run this again on the newly updated files - verify the idlines are not updated if sampleName= is already present
        // Create a new fasta list file with the updated fasta files
        val filesToUpdate2 = File(fastaOutputDir, "fastaCreateFileNames2.txt")
        filesToUpdate2.writeText(fileList.joinToString("\n") { "${it}\t${File(it).nameWithoutExtension}" })

        // run the annotateFasta command again on the updated fasta files.  This will overwrite the existing files
        // in the outputDir
        val secondOutputDir = "${TestExtension.testOutputFastaDir}/secondOutputDir"
        File(secondOutputDir).mkdirs()
        val result2 = annotateFastas.test( "--keyfile ${filesToUpdate2} --output-dir ${secondOutputDir}")

        // Get list of fastas files in the newOutputDir
        val updatedFiles2 = File(secondOutputDir).listFiles().filter { it.extension == "fa" || it.extension == "fasta" }.map { it.absolutePath }
        assertEquals(result2.statusCode, 0)
        // verify the idlines lines of each fasta files contain only a single "sampleName=${sampleName}" string
        updatedFiles2.forEach { fastaFile ->
            val sampleName = File(fastaFile).nameWithoutExtension
            val newFilename = "${fastaOutputDir}/${File(fastaFile).name}"
            File(newFilename).forEachLine { line ->
                if (line.startsWith(">")) {
                    assertTrue(line.contains("sampleName=${sampleName}"))
                    assertTrue(line.indexOf("sampleName=${sampleName}") == line.lastIndexOf("sampleName=${sampleName}"))
                }
            }
        }

        // verify that each file in the updatedFiles list is the same as the corresponding file in the updatedFiles2 list
        updatedFiles.forEachIndexed { index, fastaFile ->

            val newFilename = "${fastaOutputDir}/${File(fastaFile).name}"
            val newFilename2 = "${secondOutputDir}/${File(fastaFile).name}"

            // compare the contents of the two files
            val file1 = File(newFilename).readLines()
            val file2 = File(newFilename2).readLines()
            assertEquals(file1, file2)
        }
    }

    @Test
    fun testAnnotateGzippedFastaCommand() {
        // This test is the same as above, but with gzipped files
        // It verifies we can read and write gzipped files and they are named properly
        val fastaInputDir = TestExtension.testInputFastaDir
        val fastaOutputDir = TestExtension.testOutputFastaDir

        // Create a List<String> of fasta files in the fastaInputDir
        var fileList = File(fastaInputDir).listFiles().filter { it.extension == "fa" || it.extension == "fasta" }.map { it.absolutePath }

        // run gzip on all the files in the fileList
        fileList.forEach { file ->
            val cmd = "gzip ${file}"
            val proc = Runtime.getRuntime().exec(cmd)
            proc.waitFor()
        }

        // Update the fileList with the gzipped names
        fileList = File(fastaInputDir).listFiles().filter { it.extension == "gz" }.map { it.absolutePath }

        // Create a tab-delimited file of fasta file names and sample names
        // The fasta file names are the full path names for the fasta files in the fastaInputDir
        // the sample names are the fasta file names just up to the first "." character
        // write the fasta file names in the first column and the sample names in the second column of  a tab-delimited
        // file  named ${fastaOutputDir}/fastaCreateFileNames.txt
        val filesToUpdate = File(fastaOutputDir, "fastaCreateFileNames.txt")
        // write the fasta file names in the first column and the sample names in the second column of  a tab-delimited
        filesToUpdate.writeText(fileList.joinToString("\n") { "${it}\t${File(it).nameWithoutExtension.substringBefore(".")}" })

        println("write keyfile to ${filesToUpdate.absolutePath}")

        // Test the AnnotateFasta class
        val annotateFastas = AnnotateFastas()
        val result = annotateFastas.test( "--keyfile ${filesToUpdate} --threads 2 --output-dir ${TestExtension.testOutputFastaDir}")
        assertEquals(result.statusCode, 0)

        // get a list of fasta files created in the fastaOutputDir, as a List<String> in variable named updatedFiles
        val updatedFiles = File(fastaOutputDir).listFiles().filter { it.extension == ".gz" }.map { it.absolutePath }

        // verify the idlines of each fasta file were updated to include
        // "sampleName=${sampleName}" where sampleName is the fasta file name minus the extension
        // These are gzipped files, so you must read the compressed file and decompress it to get the fasta file

        updatedFiles.forEach { fastaFile ->
            val sampleName = File(fastaFile).nameWithoutExtension.substringBefore(".")
            val newFilename = "${fastaOutputDir}/${File(fastaFile).name}"
            File(newFilename).forEachLine { line ->
                if (line.startsWith(">")) {
                    assertTrue(line.contains("sampleName=${sampleName}"))
                }
            }
        }

        println("Running the second time")
        // run this again on the newly updated files - verify the idlines are not updated if sampleName= is already present
        // Create a new fasta list file with the updated fasta files
        val filesToUpdate2 = File(fastaOutputDir, "fastaCreateFileNames2.txt")
        filesToUpdate2.writeText(fileList.joinToString("\n") { "${it}\t${File(it).nameWithoutExtension.substringBefore(".")}" })

        // run the annotateFasta command again on the updated fasta files.  This will overwrite the existing files
        // in the outputDir
        val secondOutputDir = "${TestExtension.testOutputFastaDir}/secondOutputDir"
        // create the new outputDir
        File(secondOutputDir).mkdirs()
        val result2 = annotateFastas.test( "--keyfile ${filesToUpdate2} --output-dir ${secondOutputDir}")

        // Get list of fastas files in the newOutputDir
        val updatedFiles2 = File(secondOutputDir).listFiles().filter { it.extension == ".gz" }.map { it.absolutePath }
        assertEquals(result2.statusCode, 0)
        // verify the idlines lines of each fasta files contain only a single "sampleName=${sampleName}" string
        updatedFiles2.forEach { fastaFile ->
            val sampleName = File(fastaFile).nameWithoutExtension.substringBefore(".")
            val newFilename = "${fastaOutputDir}/${File(fastaFile).name}"
            File(newFilename).forEachLine { line ->
                if (line.startsWith(">")) {
                    assertTrue(line.contains("sampleName=${sampleName}"))
                    assertTrue(line.indexOf("sampleName=${sampleName}") == line.lastIndexOf("sampleName=${sampleName}"))
                }
            }
        }

        // verify that each file in the updatedFiles list is the same as the corresponding file in the updatedFiles2 list
        updatedFiles.forEachIndexed { index, fastaFile ->

            val newFilename = "${fastaOutputDir}/${File(fastaFile).name}"
            val newFilename2 = "${secondOutputDir}/${File(fastaFile).name}"

            // compare the contents of the two files
            val file1 = File(newFilename).readLines()
            val file2 = File(newFilename2).readLines()
            assertEquals(file1, file2)
        }
    }
}