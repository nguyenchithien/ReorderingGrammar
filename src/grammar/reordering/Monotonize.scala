package grammar.reordering

import scala.io.Source
import java.io.PrintWriter
import grammar.reordering.alignment.AlignmentCanonicalParser
import grammar.reordering.alignment.PhrasePairExtractor

object Monotonize {

  private case class Config(
      sourceFN : String = "",
      alignmentFN : String = "",
      // wordClassFile : String = null,
      // outputPrefix : String = "",
      // threads: Int = 1,
      // batchEM: Boolean = true,
      // binarySplits: Int = 10,
      // narySplits  : Int = 1,
      // onlineBatchSize : Int = 10000,
      // threadBatchSize : Int = 1000,
      // hardEMbestK : Int = -1,
      // randomnessInEstimation : Double = 0.0,
      // onlineAlpha:Double = 0.6,
      // initGrammarFN : String = null,
      // iterations : Int = 30,
      // convergenceThreshold : Double = -1,
      attachLeft  : Boolean = true,
      // attachRight : Boolean = true,
      // attachTop   : Boolean = true,
      // attachBottom: Boolean = true,
      
      outAlignmentsFN : String = null,
      outSentencesFN : String = null
  )

  private val argumentParser = new scopt.OptionParser[Config]("ReorderingGrammar") {

      head("ReorderingGrammar", "0.1")

      opt[String]('s', "sourceFile") required() action { (x, c) =>
        c.copy(sourceFN = x)
      }
      
      opt[String]('a', "alignmentsFile") required() action { (x, c) =>
        c.copy(alignmentFN = x)
      }
      
      // opt[String]('c', "wordClassFile") action { (x, c) =>
      //   c.copy(wordClassFile = x)
      // }
      
      // opt[String]('o', "outputPrefix") required() action { (x, c) =>
      //   c.copy(outputPrefix = x)
      // }
      
      // opt[String]("binarySplits") required() action { (x, c) =>
      //   c.copy(binarySplits = x.toInt)
      // }
      
      // opt[String]("narySplits") required() action { (x, c) =>
      //   c.copy(narySplits = x.toInt)
      // }
      
      // opt[Int]('t', "threads") action { (x, c) =>
      //   c.copy(threads = x)
      // }
      
      // opt[String]('g', "initGrammarFN") action { (x, c) =>
      //   c.copy(initGrammarFN = x)
      // }
      
      opt[Boolean]("nullAttachLeft") action { (x, c) =>
        c.copy(attachLeft = x)
      } required()
      
      // opt[Boolean]("nullAttachRight") action { (x, c) =>
      //   c.copy(attachRight = x)
      // }
      
      // opt[Boolean]("nullAttachTop") action { (x, c) =>
      //   c.copy(attachTop = x)
      // }
      
      // opt[Boolean]("nullAttachBottom") action { (x, c) =>
      //   c.copy(attachBottom = x)
      // }
      
      // opt[Int]("hard_EM_best_K") action { (x, c) =>
      //   c.copy(hardEMbestK = x)
      // } text ("How many Kbest for hard EM iterations (first iteration is always soft EM; for <=0 only soft EM will be used always)")
      
      // opt[Double]("randomnessInEstimation") action { (x, c) =>
      //   c.copy(randomnessInEstimation = x)
      // }
      
      // opt[Int]('b', "threadBatchSize") action { (x, c) =>
      //   c.copy(threadBatchSize = x)
      // }
      
      // opt[Int]('i', "iterations") action { (x, c) =>
      //   c.copy(iterations = x)
      // }

      // opt[Double]("convergenceThreshold") action { (x, c) =>
      //   c.copy(convergenceThreshold = x)
      // }
      
      // opt[Unit]("onlineEM") action { (_, c) =>
      //   c.copy(batchEM = false)
      // }
      
      // opt[Int]("onlineBatchSize") action { (x, c) =>
      //   c.copy(onlineBatchSize = x)
      // }
      
      // opt[Double]("onlineAlpha") action { (x, c) =>
      //   c.copy(onlineAlpha = x)
      // }
      
      //     opt[String]("outAlignments") action { (x, c) =>
      //       c.copy(outAlignmentsFN = x)
      //     }
      
      opt[String]("outSentences") action { (x, c) =>
        c.copy(outSentencesFN = x)
      } required()

      help("help") text("prints this usage text")

  }

  private def loadSents(file: String) : List[String] = Source.fromFile(file).getLines().toList
  
  def main(args: Array[String]) : Unit = {
    argumentParser.parse(args, Config()) map { config =>
      val rawSentences : List[String]  = loadSents(config.sourceFN)
      val rawAlignments : List[String] = loadSents(config.alignmentFN)


      val outSentPW = if (config.outSentencesFN == null) {
        null
      } else {
        new PrintWriter(config.outSentencesFN)
      }

      val outAlignPW = if (config.outAlignmentsFN == null) {
        null
      } else {
        new PrintWriter(config.outAlignmentsFN)
      }
      
      System.err.println("STARTED MONOTONIZING")
      
      var processed = 0
      val sentsCount = rawSentences.size
      
      (rawSentences zip rawAlignments).foreach{ case (sent, align) =>
        val a = AlignmentCanonicalParser.extractAlignment(align)
        val words = sent.split(" +").toList
        val n = words.size
        val spans = PhrasePairExtractor.findPhrases(a, n)
        val (phrasedWords, phrasedAlignments) = PhrasePairExtractor.fakeAlignmentAndFakeWords(words, spans)
        
        val (newSent, newA) = do_fucking_reordering(phrasedWords, phrasedAlignments, config.attachLeft)
        
        outSentPW.println(
            newSent.
              flatMap{PhrasePairExtractor.unfakePhrase(_)}.
              mkString(" ")
              )
        // whatever
        // this will not work because you messed up things with phrasedAlignments
        // outAlignPW.println(newA.map{case (i, j) => s"$i-$j"}.mkString(" "))
        
        processed += 1
        if(processed % 1000 == 0){
          System.err.println(s"$processed/$sentsCount phrase merging")
        }
      }
      
      if (outSentPW  != null)  outSentPW.close()
      if (outAlignPW != null) outAlignPW.close()
      System.err.println("DONE MONOTONIZING")
      
      
    } getOrElse {
      System.err.println("arguments are bad")
    }
  }
  
  def do_fucking_reordering(
      phrasedWords:List[String], 
      a:Set[(Int, Int)], 
      attachLeft:Boolean) : (List[String], Set[(Int, Int)]) = {
    val n = phrasedWords.size
    val mapping = Array.fill(n)(-1.0)
    
    a.groupBy(_._1).foreach{ case (i, points) =>
      val size = points.size.toDouble
      val target = points.map(_._2).sum/size
      mapping(i) = target
    }
    
    //find the leftmost aligned "word"
    var leftMostAligned = 0
    while(leftMostAligned < mapping.size && mapping(leftMostAligned)<0){
      leftMostAligned+=1
    }
    
    if(leftMostAligned < mapping.size && leftMostAligned>0){
      for(i <- 0 until leftMostAligned){
        mapping(i) = mapping(leftMostAligned)
      }
    }
    
    //find the rightmost aligned "word"
    var rightMostAligned = mapping.size-1
    while(rightMostAligned > 0 && mapping(rightMostAligned)<0){
      rightMostAligned-=1
    }
    
    if(rightMostAligned < mapping.size-1 && rightMostAligned>=0){
      for(i <- rightMostAligned+1 until mapping.size){
        mapping(i) = mapping(rightMostAligned)
      }
    }
    
    //solve the unaligned words in the middle
    if(attachLeft){
      for(i <- 1 until mapping.size){
        if(mapping(i)<0){
          mapping(i)=mapping(i-1)
        }
      }
    }else{
      for(i <- mapping.size-2 to 0 by -1){
        if(mapping(i)<0){
          mapping(i)=mapping(i+1)
        }
      }
    }
    
    
    // find the fucking mapping
    val oldPosToNewPosMapping =
      (0 to mapping.size-1).sortBy(mapping(_)).zipWithIndex.toMap
      
    // new alignments
    val newA = a.map{case (i, j) => (oldPosToNewPosMapping(i), j)}
    
    // new words
    val newSent = phrasedWords.zipWithIndex.
                    map{ case (word, i) =>
                      (word, oldPosToNewPosMapping(i))
                    }.sortBy(_._2).map{_._1}
    
    (newSent, newA)
  }

}
