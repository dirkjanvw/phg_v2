package net.maizegenetics.phgv2.cli

import biokotlin.featureTree.Genome
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFails

class CreateRangesTest {


    companion object {
        val tempDir = "${System.getProperty("user.home")}/temp/phgv2Tests/tempDir/"

        @JvmStatic
        @BeforeAll
        fun setup() {
            File(tempDir).mkdirs()
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            File(tempDir).deleteRecursively()
        }
    }
    @Test
    fun evaluateMethods() {
        assertEquals(2, 2)
        val testGffPath = "src/test/resources/net/maizegenetics/phgv2/cli/zm_b73v5_test.gff3.gz"
        val genes = Genome.fromGFF(testGffPath).genes()

        val cr = CreateRanges()

        val obsIdList01 = cr.idMinMaxBounds(genes, "gene", 0)
        val obsIdList02 = cr.idMinMaxBounds(genes, "cds", 0)
        val obsIdList03 = cr.idMinMaxBounds(genes, "gene", 100)

        val obsBedList01 = cr.generateBedRows(obsIdList01, genes)
        val obsBedList02 = cr.generateBedRows(obsIdList01, genes, ",")
        val obsBedList03 = cr.generateBedRows(obsIdList02, genes, featureId = "biotype")

        assertEquals(2, obsIdList01.size)
        assertEquals(obsIdList01[0], Pair(34616, 40203)) // should be 34616
        assertEquals(obsIdList01[1], Pair(41213, 46761))

        assertEquals(2, obsIdList02.size)
        assertEquals(obsIdList02[0], Pair(34721, 38365))
        assertEquals(obsIdList02[1], Pair(41526, 45912))

        assertEquals(2, obsIdList03.size)
        assertEquals(obsIdList03[0], Pair(34516, 40303))
        assertEquals(obsIdList03[1], Pair(41113, 46861))

        assertEquals(2, obsBedList01.size)
        assertEquals(obsBedList01[0], "chr1\t34616\t40203\tZm00001eb000010\t0\t+")
        assertEquals(obsBedList02[0], "chr1,34616,40203,Zm00001eb000010,0,+")
        assertEquals(obsBedList03[0], "chr1\t34721\t38365\tprotein_coding\t0\t+")

        assertFails {
            cr.idMinMaxBounds(genes, "geeeene", 0)
        }
    }

    @Test
    fun testCreateRangesCli() {
        val testGffPath = "src/test/resources/net/maizegenetics/phgv2/cli/zm_b73v5_test.gff3.gz"
        val command = CreateRanges()

        val result = command.test("--gff $testGffPath")
        assertEquals(result.statusCode, 0)
        assertEquals(command.gff, testGffPath)
        assertEquals(command.boundary, "gene")
        assertEquals(command.pad, 0)
    }

    @Test
    fun testMissingGFFCLI() {
        val command = CreateRanges()

        val result = command.test("")
        assertEquals(result.statusCode, 1)
        assertEquals("Usage: create-ranges [<options>]\n" +
                "\n" +
                "Error: invalid value for --gff: --gff must not be blank\n",result.output)

    }

    @Test
    fun testFileOutput() {

        val testGffPath = "src/test/resources/net/maizegenetics/phgv2/cli/zm_b73v5_test.gff3.gz"
        val command = CreateRanges()

        val outputFileName = "${tempDir}test.bed"

        val result = command.test("--gff $testGffPath --output $outputFileName")
        assertEquals(result.statusCode, 0)
        assertEquals(command.gff, testGffPath)
        assertEquals(command.boundary, "gene")
        assertEquals(command.pad, 0)
        assertEquals(command.output, outputFileName)

        val lines = File(outputFileName).bufferedReader().readLines()
        assertEquals("chr1\t34616\t40203\tZm00001eb000010\t0\t+", lines[0])
        assertEquals("chr1\t41213\t46761\tZm00001eb000020\t0\t-", lines[1])

    }
}
