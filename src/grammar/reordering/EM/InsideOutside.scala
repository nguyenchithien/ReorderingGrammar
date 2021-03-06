package grammar.reordering.EM

import grammar.reordering.representation._
import grammar.reordering.representation.Probability.{LogNil, LogOne, sum, product}
import java.io.PrintWriter
import scala.collection.parallel.ForkJoinTaskSupport
import grammar.reordering.alignment.AlignmentCanonicalParser
import grammar.reordering.alignment.AlignmentForestParserWithTags
import grammar.reordering.parser.KBestExtractor
import scala.util.Random
import grammar.reordering.parser.SimpleTreeNode

object InsideOutside {
  
  def extractRuleCounts(chart:Chart) : Map[Rule, Int] = {
    val n = chart.size
    var ruleCounts = scala.collection.mutable.Map[Rule, Int]().withDefaultValue(0)
    for(span <- 1 to n){
      for(i <- 0 until n-span+1){
        val j = i + span - 1
        
        val it = chart(i)(j).iterator()
        while(it.hasNext()){
          it.advance()
          for(edge <- it.value().edges){
            ruleCounts(edge.rule) += 1
          }
        }
        
      }
    }
    
    ruleCounts.toMap
  }
  
  def inside(chart:Chart, g:Grammar) : Unit = {
    val n = chart.size
    
    for(i <- 0 until n){
      val it = chart(i)(i).iterator()
      
      // processing PretermRules
      while(it.hasNext()){
        it.advance()
        val nonTermSpan = it.value()
        nonTermSpan.edges.foreach{ edge =>
          if(edge.rule.isInstanceOf[PretermRule]){
            edge.inside = edge.rule.prob
          }else{
            edge.inside = LogNil // just for the moment
          }
        }
        nonTermSpan.inside =
          sum(nonTermSpan.edges.map{_.inside}.toList)
      }
      
      //processing unary InnerRules
      val itUnary = chart(i)(i).iterator()
      while(itUnary.hasNext()){
        itUnary.advance()
        val nonTermSpan = itUnary.value()
        nonTermSpan.edges.foreach{ edge =>
          if(edge.rule.isInstanceOf[InnerRule]){
            val rhs = edge.rule.asInstanceOf[InnerRule].rhs.head
            edge.inside = edge.rule.prob * chart(i)(i).get(rhs).inside
          }
        }
        nonTermSpan.inside =
          sum(nonTermSpan.edges.map{_.inside}.toList)
      }
    }
    
    for(span <- 2 to n){
      for(i <- 0 until n-span+1){
        val j = i + span - 1
        
        var allEdges:List[Edge] = List()
        val edgesIt = chart(i)(j).iterator()
        while(edgesIt.hasNext()){
          edgesIt.advance()
          allEdges ++= edgesIt.value().edges
        }
        
        val (unaryEdges, naryEdges) = allEdges.partition(_.rule.asInstanceOf[InnerRule].rhs.size == 1)

        for(processingUnary <- List(false, true)){
          val edgesToProcess = if(processingUnary) unaryEdges else naryEdges
          for(edge <- edgesToProcess){
            edge.inside = edge.rule.prob * 
                             product(edge.children.map{ case (start:Int, end:Int, childNonTerm:NonTerm) =>
                               val nonTermSpan = chart(start)(end).get(childNonTerm)
                               nonTermSpan.inside
                             })
          }
  
          val nonTermSpanIt = chart(i)(j).iterator()
          while(nonTermSpanIt.hasNext()){
            nonTermSpanIt.advance()
            val nonTermSpan = nonTermSpanIt.value()
            nonTermSpan.inside =  sum(nonTermSpan.edges.map{_.inside}.toList)
          }
        }
      }
    }
  }
  
  def outside(chart:Chart, g:Grammar) : Unit = {
    val n = chart.size
    
    for(i <- 0 until n){
      for(j <- 0 until n){
        val it = chart(i)(j).iterator()
        while(it.hasNext()){
          it.advance()
          it.value().outside = LogNil
        }
      }
    }
    chart(0)(n-1).get(g.ROOT).outside = LogOne
    
    for(span <- n to 2 by -1){
      for(i <- 0 until n-span+1){
        val j = i + span - 1
        
        var unaryEdgesWithNTS = List[(Edge, NonTermSpan)]()
        var naryEdgesWithNTS  = List[(Edge, NonTermSpan)]()
        val it = chart(i)(j).iterator()
        while(it.hasNext()){
          it.advance()
          val nonTermSpan =  it.value()
          val edges = nonTermSpan.edges
          for(edge <- edges){
            if(edge.rule.asInstanceOf[InnerRule].rhs.size == 1){
              unaryEdgesWithNTS ::= (edge, nonTermSpan)
            }else{
              naryEdgesWithNTS ::= (edge, nonTermSpan)
            }
          }
        }

        for(processingUnary <- List(true, false)){
          val edgesWithNTS = if(processingUnary) unaryEdgesWithNTS else naryEdgesWithNTS
          for((edge, nonTermSpan) <- edgesWithNTS){
            for((start, end, childNonTerm) <- edge.children){
              chart(start)(end).get(childNonTerm).outside += nonTermSpan.outside * edge.inside / chart(start)(end).get(childNonTerm).inside
            }
          }
        }
      }
    }
    
    for(i <- 0 until n){
      val it = chart(i)(i).iterator()
      while(it.hasNext()){
        it.advance()
        val nonTermSpan = it.value()
        for(edge <- nonTermSpan.edges){
          edge.rule match {
            case InnerRule(lhs, List(rhs), p) =>
              val childNTS = chart(i)(i).get(rhs)
              childNTS.outside += nonTermSpan.outside * edge.inside / childNTS.inside
            case PretermRule(_, _, _) =>
          }
        }
      }
    }
  }
  
  def expectation(
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
                 rightBranching:Boolean,
                 extractTrees:Boolean,
                 maxRuleProduct:Boolean,
                 maxRuleSum:Boolean
                    ) : (Map[Rule, Double], List[List[SimpleTreeNode]], Probability) = {
    g.voc.lock()
    val trainingBatches = if(threads>1){
                            val l = trainingData.zipWithIndex.grouped(batchSize).toList.par
                            l.tasksupport = 
                              new ForkJoinTaskSupport(new scala.concurrent.forkjoin.ForkJoinPool(threads))
                            l
                          }else{
                            trainingData.zipWithIndex.grouped(batchSize).toList
                          }
    var processed = 0
    val veryBeginingTime = System.currentTimeMillis()
    var startTime = System.currentTimeMillis()
    val totalSentsToProcess = trainingData.size

    val parallelExpectations:Seq[(scala.collection.Map[Rule, Double], List[(Int, List[SimpleTreeNode])], Probability)] = trainingBatches.map{ miniBatch =>
      val miniBatchResults:Seq[(scala.collection.Map[Rule, Double], List[(Int, List[SimpleTreeNode])], Probability)] = miniBatch.map{ case ((sent:String, alignment:String, posSequence:POSseq), sentIndex:Int) =>
        // System.err.println("processing sentence  \""+sent+"\"")
        // System.err.println("processing alignment \""+alignment+"\"")

        val a = AlignmentCanonicalParser.extractAlignment(alignment)
        val s = sent.split(" +").toList
        
        val alignmentParser = new AlignmentForestParserWithTags(
            g=g,
            attachLeft=attachLeft,
            attachRight=attachRight,
            attachTop=attachTop,
            attachBottom=attachBottom,
            beSafeBecauseOfPruning=true,
            canonicalOnly=canonicalOnly,
            rightBranching=rightBranching)
  
        val chart:Chart = alignmentParser.parse(sent=s, a=a, tags=posSequence)
        
        this.inside(chart, g)
        this.outside(chart, g)

        if(sentIndex % 1000 == 1){
          val debug_k = 1
          val debug_maxRuleProduct = false
          val debug_maxRuleSum     = true
          val kBestTrees = KBestExtractor.extractKbest(g, chart, debug_k, debug_maxRuleProduct, debug_maxRuleSum)
          kBestTrees.foreach { tree => System.err.println("\nsentence " + sentIndex + " " + tree.toPennStringIndented(s)) }
        }

        val (chartExpectations, trees, sentProb) = if(hardEMtopK > 0){
          val (chartExpectations, trees, sentProb) = this.computeHardExpectedCountPerChart(chart, g, randomness, hardEMtopK, maxRuleProduct, maxRuleSum)
          (chartExpectations, trees, sentProb)
        }else{
          val (chartExpectations, sentProb) = this.computeSoftExpectedCountPerChart(chart, g, randomness)
          (chartExpectations, List(), sentProb)
        }
        
        val n = chart.size

        if(processed % 10 == 0 && processed != 0){
          System.err.print(".")
        }
        if(processed % 1000 == 0){
          var newTime = System.currentTimeMillis()
          var period = (newTime - startTime) / 1000
          System.err.println()
          System.err.print(s"$processed/$totalSentsToProcess | last chunk processed for $period s\t")
          val pastMins = (newTime-veryBeginingTime)/60000
          if(processed != 0 && pastMins != 0){
            val futureMins:Int = (totalSentsToProcess-processed).toInt/(processed/pastMins).toInt
            val partialFutureHours = futureMins/60
            val partialFutureMins = futureMins%60
            System.err.print(s"| time left $partialFutureHours h $partialFutureMins m\t")
          }
          System.err.println()
          startTime = newTime
        }
        processed += 1

        (chartExpectations, List((sentIndex, trees)), sentProb)
      } // .reduce( (a:(scala.collection.Map[Rule, Double], Probability),b:(scala.collection.Map[Rule, Double], Probability)) => sumExpectations(a, b) )
      val (a, b, c) = sumManyExpectations(miniBatchResults)
      // (a.toMap, b)
      (a, b, c)
    }.seq // .reduce( (a,b) => sumExpectations(a,b) )
    
    val (
        expectedCounts:scala.collection.Map[Rule, Double],
        treebank:List[(Int, List[SimpleTreeNode])],
        allSplitLikelihood:Probability) = sumManyExpectations(parallelExpectations)
    
    (expectedCounts.toMap.withDefaultValue(0.0), treebank.map{_._2}, allSplitLikelihood)
  }
  
  def maximization(g:Grammar, expectedCounts:scala.collection.Map[Rule, Double]) : Grammar = {
    System.err.println(s"STARTING maximization")
    val t1 = System.currentTimeMillis()
    var newRules = List[Rule]()
    val normalizations = scala.collection.mutable.Map[NonTerm, Double]().withDefaultValue(0.0)

    for((rule, counts) <- expectedCounts){
      if(!counts.isNaN() && !counts.isInfinite()){
        normalizations(rule.lhs) = normalizations(rule.lhs) + counts
      }else{
        System.err.println(s"wtf $counts")
      }
    }
    
    for((rule, counts) <- expectedCounts){
      val newProb = if(counts == 0.0){
        LogNil
      } else {
        val normalizer = normalizations(rule.lhs)
        val p = counts/normalizer
        if(p.isNaN() || p.isInfinite()){
          println(s"problem $counts divided by $normalizer")
        }
        Probability(p)
      }
      rule match {
        case InnerRule(lhs, rhs, _) =>
          newRules ::= InnerRule(lhs, rhs, newProb)
        case PretermRule(lhs, word, _) =>
          newRules ::= PretermRule(lhs, word, newProb)
      }
    }

    val newGrammar = new Grammar(rulesArg = newRules, latentMappings = g.latentMappings, voc = g.voc, nonTerms = g.nonTerms )
    val t2 = System.currentTimeMillis()
    val period = t2 - t1
    System.err.println(s"DONE maximization, took $period ms")
    
    newGrammar
  }
  
  private def sumManyExpectations(toSum:Traversable[(scala.collection.Map[Rule, Double], List[(Int, List[SimpleTreeNode])], Probability)]) : (scala.collection.Map[Rule, Double], List[(Int, List[SimpleTreeNode])], Probability) = {
    val totalExp = scala.collection.mutable.Map[Rule, Double]()
    var totalLikelihood = LogOne
    for((aExp, _, aLikelihood) <- toSum){
      totalLikelihood *= aLikelihood
      
      for((k, v) <- aExp){
        totalExp(k) = totalExp.getOrElse(k, 0.0) + v
      }
    }
    
    val sortedTrees = toSum.toList.map{_._2}.flatten.sortBy(_._1)
    
    (totalExp, sortedTrees, totalLikelihood)
  }

  private def sumExpectations(
      a:(Map[Rule, Double], Probability),
      b:(Map[Rule, Double], Probability))
      : (Map[Rule, Double], Probability) = { 
    val (aExp, aLikelihood) = a
    val (bExp, bLikelihood) = b
    val cExp = scala.collection.mutable.Map[Rule, Double]()
    for((k, v) <- aExp){
      cExp(k) = v
    }
    for((k, v) <- bExp){
      cExp(k) = cExp.getOrElse(k, 0.0) + v
    }
    // val cExp = (aExp.keySet ++ bExp.keySet).map{key => key -> (aExp.getOrElse(key, 0.0) + bExp.getOrElse(key, 0.0))}.toMap
    val cLikelihood = aLikelihood * bLikelihood
    System.err.print("+")
    (cExp.toMap, cLikelihood)
  }
  
  /**
   * MUST NOT be parallelized because it contains mutable vocabulary (and maybe some other things)
   * anyway this phase is really fast so again NO NEED for parallelization
   */
  def initialIteration(
      trainingData:List[(String, String, POSseq)],
      attachLeft:Boolean,
      attachRight:Boolean,
      attachTop:Boolean,
      attachBottom:Boolean,
      canonicalOnly:Boolean,
      rightBranching:Boolean
      ) : Grammar = {
    val posTags = scala.collection.mutable.Set[String]()
    for((_, _, poss) <- trainingData){
      for(positionPoss <- poss){
        for((pos, _) <- positionPoss){
          posTags += pos
        }
      }
    }
    val voc = new IntMapping()
    var allRuleCounts = scala.collection.mutable.Map[Rule, Double]().withDefaultValue(0.0)
    val nonTerms = AlignmentForestParserWithTags.createNonTermsMappingWithTags(posTags.toList)
    val g = new Grammar(
        rulesArg = List(),
        latentMappings = AlignmentForestParserWithTags.createDummyLatentMappings(nonTerms),
        nonTerms = nonTerms,
        voc = voc,
        dummy=true
        )
    var processed = 0
    trainingData.foreach{ case (sent, alignment, posSequence) =>
      val a = AlignmentCanonicalParser.extractAlignment(alignment)
      val s = sent.split(" +").toList
      s.foreach(voc(_))
      val alignmentParser = new AlignmentForestParserWithTags(
          g=g,
          attachLeft=attachLeft,
          attachRight=attachRight,
          attachTop=attachTop,
          attachBottom=attachBottom,
          beSafeBecauseOfPruning=true,
          canonicalOnly=canonicalOnly,
          rightBranching=rightBranching)
      val chart:Chart = alignmentParser.parse(sent=s, a=a, tags=posSequence)
      for((rule, count) <- this.extractRuleCounts(chart)){
        allRuleCounts(rule) = allRuleCounts(rule)+count
      }
      processed += 1
      if(processed % 10000 == 0){
        System.err.println(processed)
      }
    }
    val newRules:List[Rule] = allRuleCounts.keySet.groupBy{rule:Rule => rule.lhs}.flatMap{case (lhs:NonTerm, rules:Set[Rule]) =>
      val lhsExpectedCount:Double = rules.toList.map{allRuleCounts(_)}.sum
      rules.map{
        case rule:InnerRule   => rule.copy(p = Probability(allRuleCounts(rule) / lhsExpectedCount))
        case rule:PretermRule => rule.copy(p = Probability(allRuleCounts(rule) / lhsExpectedCount))
      }
    }.asInstanceOf[List[Rule]]

    val newGrammar = new Grammar(
        rulesArg = newRules,
        latentMappings = g.latentMappings,
        nonTerms = g.nonTerms,
        voc = voc
        )
    
    voc.lock()
    
    newGrammar
  }
  
  def computeSoftExpectedCountPerChart(chart:Chart, g:Grammar, randomness:Double) : (scala.collection.Map[Rule, Double], Probability) = {
    val n = chart.size
    val sentProb = chart(0)(n-1).get(g.ROOT).inside
    
    val ruleCountAcc = scala.collection.mutable.Map[Rule, Double]().withDefaultValue(0.0)
    
    for(span <- 1 to n){
      for(i <- 0 until n-span+1){
        val j = i + span - 1
        
        val it = chart(i)(j).iterator()
        while(it.hasNext()){
          it.advance()
          val nonTermSpan = it.value()
          for(edge <- nonTermSpan.edges){
            val expectProb = (nonTermSpan.outside * edge.inside)/sentProb
            val expect = expectProb.toDouble
            if( ! expect.isNaN && ! expect.isInfinite()){
              val noize = Random.nextDouble()*expect*randomness/100
              ruleCountAcc(edge.rule) += expect + noize
            }else{
              System.err.println(edge.toString(g.voc, g.nonTerms))
              System.err.println()
            }
          }
        }
      }
    }

    (ruleCountAcc, sentProb)
  }
  
  def computeHardExpectedCountPerChart(chart:Chart, g:Grammar, randomness:Double, k:Int, maxRuleProduct:Boolean, maxRuleSum:Boolean) : (scala.collection.Map[Rule, Double], List[SimpleTreeNode], Probability) = {
    val ruleCountAcc = scala.collection.mutable.Map[Rule, Double]().withDefaultValue(0.0)
    
    val kBestTrees = KBestExtractor.extractKbest(g, chart, k, maxRuleProduct, maxRuleSum)
    
    val sentWeight = kBestTrees.map{ tree =>
      if(maxRuleSum){
        tree.subTreeScore
      } else {
        Math.exp(tree.subTreeScore)
      }
    }.sum

    for((tree, index) <- kBestTrees.zipWithIndex){
      val treeWeight = if(maxRuleSum){
                         tree.subTreeScore
                       } else {
                         Math.exp(tree.subTreeScore)
                       }
      System.err.print("rank="+index+" ")
      System.err.print("rawScore="+tree.subTreeScore+" ")
      System.err.print("weight="+treeWeight+" ")
      // System.err.print("exp(score)="+Math.exp(tree.subTreeScore)+" ")
      // System.err.print("log(score)="+Math.log(tree.subTreeScore)+" ")
      System.err.print(tree.toPennString())
      System.err.println()

      val rules = tree.extractRules(g)
      rules.foreach{ rule =>
        val partialExpectation:Double = treeWeight/sentWeight
        val noize = Random.nextDouble()*partialExpectation*randomness/100
        ruleCountAcc(rule) += partialExpectation+noize
      }
    }
    
    val totalSentenceScore = if(sentWeight>1){
      LogOne
    }else{
      Probability(sentWeight)
    }
    
    (ruleCountAcc, kBestTrees, totalSentenceScore)
  }
  
}
