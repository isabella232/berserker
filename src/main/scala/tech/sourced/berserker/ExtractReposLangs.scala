package tech.sourced.berserker

import java.io.File

import org.apache.hadoop.fs.Path
import org.apache.log4j.Logger
import org.apache.spark.SparkConf
import org.apache.spark.sql.{Row, SparkSession}
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.treewalk.TreeWalk
import tech.sourced.berserker.git.{JGitFileIterator, RootedRepo}
import tech.sourced.berserker.model.Schema
import tech.sourced.berserker.spark.SerializableConfiguration
import tech.sourced.enry.{Enry, Guess}
import tech.sourced.siva.SivaUnpacker

import scala.util.Properties

object ExtractReposLangs {

  def main(args: Array[String]): Unit = {
    val log = Logger.getLogger(getClass.getName)

    val sparkMaster = Properties.envOrElse("MASTER", "local[*]")
    val sivaFilesPath = if (args.length > 0) { args(0) } else "hdfs://hdfs-namenode/borges/root-repositories/"
    val sivaFileLimit = if (args.length > 1) { args(1).toInt } else 10
    val numPartitions = if (args.length > 2) { args(2).toInt } else 3
    val outputPath = if (args.length > 3) { args(3) } else "lang.files"
    log.info(s"Processing up to $sivaFileLimit .siva files from $sivaFilesPath " +
      s"using $numPartitions partitions and saving results to $outputPath")

    val spark = SparkSession.builder()
      .config(new SparkConf())
      .appName("extract-language-per-file")
      .master(sparkMaster)
      .getOrCreate()
    val sc = spark.sparkContext

    // list .siva files (on Driver)
    val confBroadcast = sc.broadcast(new SerializableConfiguration(sc.hadoopConfiguration))
    var sivaFiles = FsUtils.collectSivaFilePaths(sc.hadoopConfiguration, log, new Path(sivaFilesPath))
    if (sivaFileLimit > 0) {
      sivaFiles = sivaFiles.take(Math.min(sivaFileLimit, sivaFiles.length))
    }

    val actualNumWorkers = Math.min(numPartitions, sivaFiles.length)
    val remoteSivaFiles = sc.parallelize(sivaFiles, actualNumWorkers)
    log.info(s"Actual processing: ${sivaFiles.length} .siva files in $actualNumWorkers partitions")

    // copy from HDFS and un-pack in tmp dir using siva-java (on Workers)
    val unpacked = remoteSivaFiles
      .map { sivaFile =>
        FsUtils.copyFromHDFS(confBroadcast.value.value, sivaFile)
      }
      .map { case (sivaFile, localUnpackDir) =>
        new SivaUnpacker(new File(s"$localUnpackDir/$sivaFile").getAbsolutePath).unpack(localUnpackDir)
        val fileNameInitCommit = sivaFile.substring(0, sivaFile.lastIndexOf('.'))
        (fileNameInitCommit, localUnpackDir)
      }

    // iterate every un-packed .siva
    val intermediatePerFile = unpacked
      .flatMap { case (sivaFileName, sivaUnpackDir) =>
        val log = Logger.getLogger(s"Stage: process single repo")
        log.info(s"Processing repository in $sivaUnpackDir")
        JGitFileIterator(sivaUnpackDir, sivaFileName, confBroadcast.value.value)
      }
      .filter { case (_, tree, _, _) =>
        tree.getFileMode(0) == FileMode.REGULAR_FILE || tree.getFileMode(0) == FileMode.EXECUTABLE_FILE
        //TODO(bzz): skip big well-known binaries .apk and .jar
      }
      .map { case (initHash, tree, ref, config) =>
        val log = Logger.getLogger(s"Stage: detecting a language")

        val (langName, langBytes) = guessLang(tree, log)

        val repoULID = ref.getName.split('/').last
        val repoIsFork = config.getString("remote", repoULID, "isfork")
        val repoUrls = config.getStringList("remote", repoULID, "url")
        val mainRepoUrl = if (repoUrls.isEmpty) "" else repoUrls.head

        Row(mainRepoUrl, repoIsFork.toBoolean, initHash, tree.getPathString, langName, langBytes)
      }

    val intermediatePerFileDF = spark.sqlContext.createDataFrame(intermediatePerFile, Schema.filesLang)
    intermediatePerFileDF.write
      .mode("overwrite")
      .parquet(outputPath)

    val parquetAllDF = spark.read
      .parquet(outputPath)
      .show(10)

  }

  def guessLang(tree: TreeWalk, log: Logger): (String, Int) = {
    var content = Array.emptyByteArray
    val path = tree.getPathString

    var guessed = Enry.getLanguageByFilename(path)
    if (!guessed.safe) {
      content = RootedRepo.readFile(tree.getObjectId(0), tree.getObjectReader)
      log.info(s"for $path using content size:${content.length}".getBytes)
      guessed = if (content.isEmpty) {
        Enry.unknownLanguage
      } else {
        new Guess(Enry.getLanguage(path, content), true)
      }
    }
    log.info(s"$path is ${guessed.language}")
    (guessed.language, content.length)
  }


}
