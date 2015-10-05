package grammar.reordering.EM

import grammar.reordering.representation.Probability
import grammar.reordering.representation.Probability.{LogNil}
import grammar.reordering.representation.Grammar
import grammar.reordering.representation.Rule
import grammar.reordering.representation.POSseq
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import grammar.reordering.alignment.PhrasePairExtractor
import grammar.reordering.alignment.AlignmentForestParserWithTags

object BatchEM {

  def runTraining(
                 stoppingCriteria : (Probability, Probability, Int) => Boolean,
                 output : String,
                 trainingData:List[(String, String, POSseq)],
                 initG:Grammar,
                 firstIterNum:Int,
                 threads:Int,
                 threadBatchSize:Int,
                 randomness:Double,
                 hardEMtopK:Int,
                 attachLeft:Boolean,
                 attachRight:Boolean,
                 attachTop:Boolean,
                 attachBottom:Boolean,
                 canonicalOnly:Boolean,
                 rightBranching:Boolean
                    ) : Grammar = {
    var initCounts = Map[Rule, Double]()
    for(rule <- initG.allRules){
      initCounts += rule -> 1.0
    }
    var currentCounts = initCounts
    
    var prevLikelihood = LogNil
    var currentLikelihood = LogNil // unimporant initialization
    var it = firstIterNum
    var currentG = initG
    
    val wordCount:Double = trainingData.map{_._1.split(" +").size}.sum

    do{
      val ft = new SimpleDateFormat ("HH:mm dd.MM.yyyy")
      val date = ft.format(new Date())
      System.err.println(s"Iteration $it started at $date")
      System.err.println()

      val result = if(it>0){
        if(hardEMtopK > 0){
          System.err.println("HARD-EM iteration")
        }else{
          System.err.println("SOFT-EM iteration")
        }
        iteration(trainingData, currentG, threadBatchSize, threads, randomness, hardEMtopK, attachLeft, attachRight, attachTop, attachBottom, canonicalOnly, rightBranching)
      }else{
        System.err.println("SOFT-EM iteration")
        iteration(trainingData, currentG, threadBatchSize, threads, randomness, -1, attachLeft, attachRight, attachTop, attachBottom, canonicalOnly, rightBranching)
      }
      currentG = result._1
      currentLikelihood = result._2
      
      currentG.save(output+"/grammar_"+it, dephrased=false)
      // val dePhrasedGrammar = PhrasePairExtractor.unfoldGrammarOfIdioms(currentG)
      // dePhrasedGrammar.save(output+"/grammar_"+it+".dephrased")
      currentG.save(output+"/grammar_"+it+".dephrased", dephrased=true)
      val perplexityPerWord = Math.exp(-currentLikelihood.log/wordCount)
      System.err.println()
      System.err.println(s"Grammar $it: likelihood $currentLikelihood")
      System.err.println(s"Grammar $it: Perplexity per word $perplexityPerWord")
      System.err.println()
      
      it += 1
    }while( ! stoppingCriteria(prevLikelihood, currentLikelihood, it))
      
    currentG
  }
  
  private def iteration(
                 trainingData:List[(String, String, POSseq)],
                 g:Grammar,
                 batchSize:Int,
                 threads:Int,
                 randomness:Double,
                 hardEMtopK:Int,
                 attachLeft:Boolean,
                 attachRight:Boolean,
                 attachTop:Boolean,
                 attachBottom:Boolean,
                 canonicalOnly:Boolean,
                 rightBranching:Boolean
                    ) : (Grammar, Probability) = {
    System.err.println(s"STARTED expectations")
    val t1 = System.currentTimeMillis()
    
    val (expectedCounts, likelihood) = InsideOutside.expectation(trainingData, g, batchSize, threads, randomness, hardEMtopK, attachLeft, attachRight, attachTop, attachBottom, canonicalOnly, rightBranching)
    
    val t2 = System.currentTimeMillis()
    val period = t2 - t1
    System.err.println(s"DONE expectations took $period ms")

    val newGrammar = InsideOutside.maximization(g, expectedCounts)
    (newGrammar, likelihood)
  }

}
