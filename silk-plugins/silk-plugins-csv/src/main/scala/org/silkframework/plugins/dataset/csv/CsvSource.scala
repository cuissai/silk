package org.silkframework.plugins.dataset.csv

import java.io.{BufferedReader, InputStreamReader}
import java.net.URLEncoder
import java.nio.charset.MalformedInputException
import java.util.logging.{Level, Logger}
import java.util.regex.Pattern

import org.silkframework.dataset.DataSource
import org.silkframework.entity._
import org.silkframework.entity.rdf.{SparqlEntitySchema, SparqlRestriction}
import org.silkframework.runtime.resource.Resource
import org.silkframework.util.Uri

import scala.collection.mutable.{HashMap => MMap}
import scala.io.Codec

class CsvSource(file: Resource,
                settings: CsvSettings = CsvSettings(),
                properties: String = "",
                prefix: String = "",
                uri: String = "",
                regexFilter: String = "",
                codec: Codec = Codec.UTF8,
                skipLinesBeginning: Int = 0,
                ignoreBadLines: Boolean = false,
                detectSeparator: Boolean = false,
                detectSkipLinesBeginning: Boolean = false,
               // If the text file fails to be read because of a MalformedInputException, try other codecs
                fallbackCodecs: List[Codec] = List()) extends DataSource {

  private val logger = Logger.getLogger(getClass.getName)

  // How many lines should be used for detecting the encoding or separator etc.
  final val linesForDetection = 100

  private lazy val propertyList: Seq[String] = {
    val parser = new CsvParser(Seq.empty, csvSettings)
    if (!properties.trim.isEmpty)
      parser.parseLine(properties)
    else {
      val source = getAndInitBufferedReaderForCsvFile()
      val firstLine = source.readLine()
      source.close()
      if (firstLine != null && firstLine != "") {
        parser.parseLine(firstLine).
            takeWhile(_ != null). // Break if a header field is null
            map(s => URLEncoder.encode(s, "UTF8"))
      } else {
        Seq()
      }
    }
  }

  lazy val (csvSettings, skipLinesAutomatic): (CsvSettings, Option[Int]) = {
    var csvSettings = settings
    val detectedSeparator = detectSeparatorChar()
    val separatorChar = detectedSeparator map (_.separator)
    val skipLinesBeginningAutoDetected = detectedSeparator map (_.skipLinesBeginning)
    if (detectSeparator) {
      csvSettings = csvSettings.copy(separator = separatorChar getOrElse settings.separator)
    }
    (csvSettings, skipLinesBeginningAutoDetected filter (_ => detectSkipLinesBeginning))
  }

  // automatically detect the separator, returns None if confidence is too low
  private def detectSeparatorChar(): Option[DetectedSeparator] = {
    val source = getBufferedReaderForCsvFile()
    try {
      val inputLines = (for (i <- 1 to linesForDetection)
        yield source.readLine()) filter (_ != null)
      SeparatorDetector.detectSeparatorCharInLines(inputLines, settings)
    } finally {
      source.close()
      None
    }
  }

  override def toString = file.toString

  override def retrieveSparqlPaths(restriction: SparqlRestriction, depth: Int, limit: Option[Int]): Traversable[(Path, Double)] = {
    for (property <- propertyList) yield {
      (Path(restriction.variable, ForwardOperator(prefix + property) :: Nil), 1.0)
    }
  }

  override def retrieveSparqlEntities(entityDesc: SparqlEntitySchema, entities: Seq[String] = Seq.empty): Traversable[Entity] = {

    logger.log(Level.FINE, "Retrieving data from CSV.")

    // Retrieve the indices of the request paths
    val indices =
      for (path <- entityDesc.paths) yield {
        val property = path.operators.head.asInstanceOf[ForwardOperator].property.uri.stripPrefix(prefix)
        val propertyIndex = propertyList.indexOf(property)
        if (propertyIndex == -1)
          throw new Exception("Property " + path.toString + " not found in CSV")
        propertyIndex
      }

    // Return new Traversable that generates an entity for each line
    new Traversable[Entity] {
      def foreach[U](f: Entity => U) {

        lazy val reader = getAndInitBufferedReaderForCsvFile
        val parser = new CsvParser(Seq.empty, csvSettings) // Here we could only load the required indices as a performance improvement

        // Compile the line regex.
        val regex: Pattern = if (!regexFilter.isEmpty) regexFilter.r.pattern else null

        try {
          // Iterate through all lines of the source file. If a *regexFilter* has been set, then use it to filter
          // the rows.
          var line = reader.readLine()
          var index = 0
          while (line != null) {
            if (!(properties.trim.isEmpty && 0 == index) && (regexFilter.isEmpty || regex.matcher(line).matches())) {
              logger.log(Level.FINER, s"Retrieving data from CSV [ line number :: ${index + 1} ].")

              //Split the line into values
              val allValues = parser.parseLine(line)
              if (allValues != null) {
                if (propertyList.size <= allValues.size) {

                  //Extract requested values
                  val values = indices.map(allValues(_))

                  // The default URI pattern is to use the prefix and the line number.
                  // However the user can specify a different URI pattern (in the *uri* property), which is then used to
                  // build the entity URI. An example of such pattern is 'urn:zyx:{id}' where *id* is a name of a property
                  // as defined in the *properties* field.
                  val entityURI = if (uri.isEmpty)
                    prefix + (index + 1)
                  else
                    "\\{([^\\}]+)\\}".r.replaceAllIn(uri, m => {
                      val propName = m.group(1)

                      assert(propertyList.contains(propName))
                      val value = allValues(propertyList.indexOf(propName))
                      URLEncoder.encode(value, "UTF-8")
                    })

                  //Build entity
                  if (entities.isEmpty || entities.contains(entityURI)) {
                    val entityValues = csvSettings.arraySeparator match {
                      case None =>
                        values.map(v => if (v != null) Set(v) else Set.empty[String])
                      case Some(c) =>
                        values.map(v => if (v != null) v.split(c.toString, -1).toSet else Set.empty[String])
                    }

                    f(new Entity(
                      uri = entityURI,
                      values = entityValues,
                      desc = entityDesc
                    ))
                  }
                } else {
                  // Bad line
                  if (!ignoreBadLines) {
                    assert(propertyList.size <= allValues.size, s"Invalid line ${index + 1}: '$line' in resource '${file.name}' with ${allValues.size} elements. Expected number of elements ${propertyList.size}.")
                  }
                }
              }
            }
            index += 1
            line = reader.readLine()
          }
        } finally {
          reader.close()
        }
      }
    }
  }

  // Skip lines that are not part of the CSV file, headers may be included
  private def initBufferedReader(reader: BufferedReader): Unit = {
    val nrLinesToSkip = skipLinesAutomatic getOrElse skipLinesBeginning
    for (i <- 1 to nrLinesToSkip)
      reader.readLine() // Skip line
  }

  private def getAndInitBufferedReaderForCsvFile(): BufferedReader = {
    val reader = getBufferedReaderForCsvFile()
    initBufferedReader(reader)
    reader
  }

  private def getBufferedReaderForCsvFile(): BufferedReader = {
    getBufferedReaderForCsvFile(codecToUse)
  }

  private def getBufferedReaderForCsvFile(codec: Codec): BufferedReader = {
    val inputStream = file.load
    new BufferedReader(new InputStreamReader(inputStream, codec.decoder))
  }

  lazy val codecToUse: Codec = {
    if(fallbackCodecs.isEmpty) {
      codec
    } else {
      pickWorkingCodec
    }
  }

  private def pickWorkingCodec: Codec = {
    val tryCodecs = codec :: fallbackCodecs
    for (c <- tryCodecs) {
      val reader = getBufferedReaderForCsvFile(c)
      // Test read
      try {
        var line = reader.readLine()
        while (line != null) {
          line = reader.readLine()
        }
        return c
      } catch {
        case e: MalformedInputException =>
          logger.fine(s"Codec $c failed for input file ${file.name}")
      } finally {
        reader.close()
      }
    }
    codec
  }

  override def retrieveTypes(limit: Option[Int] = None): Traversable[(String, Double)] = {
    Seq((classUri, 1.0))
  }

  override def retrievePaths(t: Uri, depth: Int = 1, limit: Option[Int] = None): IndexedSeq[Path] = {
    if (classUri == t.uri) {
      val props = for (property <- propertyList) yield {
        Path(prefix + property)
      }
      props.toIndexedSeq
    } else {
      IndexedSeq.empty[Path]
    }
  }

  override def retrieve(entitySchema: EntitySchema, limitOpt: Option[Int] = None): Traversable[Entity] = {
    if (entitySchema.filter.operator.isDefined) {
      ??? // TODO: Implement Restriction handling!
    }
    val entities = retrieveSparqlEntities(SparqlEntitySchema(paths = entitySchema.paths))
    limitOpt match {
      case Some(limit) =>
        entities.take(limit)
      case None =>
        entities
    }
  }

  private def classUri = prefix + "CsvTable"
}

object SeparatorDetector {
  private val separatorList = Seq(',', '\t', ';', '|', '^')

  def detectSeparatorCharInLines(inputLines: Seq[String],
                                 settings: CsvSettings): Option[DetectedSeparator] = {
    if (inputLines.isEmpty) {
      return None
    }
    val separatorCharDist = for (separator <- separatorList) yield {
      // Test which separator has the lowest entropy
      val csvParser = new CsvParser(Seq.empty, settings.copy(separator = separator))
      val fieldCountDist = new MMap[Int, Int]
      for (line <- inputLines) {
        val fields = csvParser.parseLine(line)
        if (fields != null) {
          // Empty lines return null in the previous call
          val fieldCount = fields.size
          fieldCountDist.put(fieldCount, fieldCountDist.getOrElse(fieldCount, 0) + 1)
        }
      }
      (separator, fieldCountDist.toMap)
    }
    // Filter out
    pickBestSeparator(separatorCharDist.toMap, inputLines, settings)
  }

  // For entropy equation, see https://en.wikipedia.org/wiki/Entropy_%28information_theory%29
  def entropy(distribution: Map[Int, Int]): Double = {
    if (distribution.isEmpty)
      return 0.0
    val overallCount = distribution.values.sum
    if (overallCount == 0)
      return 0.0
    var sum = 0.0

    for ((_, count) <- distribution if count > 0) {
      val probability = count.toDouble / overallCount
      sum += probability * math.log(probability)
    }
    -sum
  }

  // Filter out separators that don't split most of the input lines, then pick the one with the lowest entropy
  private def pickBestSeparator(separatorDistribution: Map[Char, Map[Int, Int]],
                                inputLines: Seq[String],
                                csvSettings: CsvSettings): Option[DetectedSeparator] = {
    assert(separatorDistribution.size == 0 || separatorDistribution.forall(d => d._2.size > 0 && d._2.values.sum > 0))
    // Ignore characters that did not split anything
    val candidates = separatorDistribution filter { case (c, dist) =>
      val oneFieldCount = dist.getOrElse(1, 0)
      val sum = dist.values.sum
      // Separators with too many 1-field lines are filtered out
      oneFieldCount.toDouble / sum < 0.5
    }
    val charEntropy = candidates map { case (c, dist) =>
      (c, entropy(dist))
    }
    pickSeparatorBasedOnEntropy(separatorDistribution, charEntropy, inputLines, csvSettings)
  }

  // Pick the separator with the lowest entropy of its field count distribution
  private def pickSeparatorBasedOnEntropy(separatorDistribution: Map[Char, Map[Int, Int]],
                                          charEntropy: Map[Char, Double],
                                          inputLines: Seq[String],
                                          csvSettings: CsvSettings): Option[DetectedSeparator] = {
    val lowestEntropySeparator = charEntropy.toSeq.sortWith(_._2 < _._2).headOption
    // Entropy must be < 0.1, which means that at most 6 out of [[linesForDetection]] lines may have a different number of fields than the majority
    val separator = lowestEntropySeparator filter (_._2 < 0.1) map (_._1)
    separator map { c =>
      val dist = separatorDistribution(c)
      val numberOfFields = dist.toSeq.sortWith(_._2 > _._2).head._1
      val skipLinesAtBeginning = detectSkipLinesBasedOnDetectedSeparator(inputLines, numberOfFields, c, csvSettings)
      DetectedSeparator(c, numberOfFields, skipLinesAtBeginning)
    }
  }

  private def detectSkipLinesBasedOnDetectedSeparator(inputLines: Seq[String],
                                                      numberOfFields: Int,
                                                      separator: Char,
                                                      csvSettings: CsvSettings): Int = {
    val parser = new CsvParser(Seq.empty, csvSettings.copy(separator = separator))
    inputLines takeWhile { line =>
      val fields = parser.parseLine(line)
      fields == null || line.split(separator).size != numberOfFields
    } size

  }
}

/**
 * The return value of the separator detection
 * @param separator the character used for separating fields in CSV
 * @param numberOfFields the detected number of fields when splitting with this separator
 */
case class DetectedSeparator(separator: Char, numberOfFields: Int, skipLinesBeginning: Int)