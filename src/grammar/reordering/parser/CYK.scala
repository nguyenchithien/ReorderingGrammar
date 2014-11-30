package grammar.reordering.parser

import grammar.reordering.representation.Chart
import grammar.reordering.representation.Grammar
import grammar.reordering.representation.ChartHelper
import grammar.reordering.representation.`package`.PretermRule
import grammar.reordering.representation.`package`.NonTermSpan
import grammar.reordering.representation.`package`.NonTerm
import grammar.reordering.representation.`package`.Word
import grammar.reordering.representation.`package`.Edge
import grammar.reordering.representation.`package`.Rule
import grammar.reordering.representation.`package`.InnerRule
import grammar.reordering.EM.InsideOutside
import grammar.reordering.representation.Probability
import grammar.reordering.representation.Probability.{sum, LogNil}
import java.util.Collections
import java.util.Arrays

object CYK {

  case class DotEdge(afterDot:List[NonTerm], rule:Rule, splits:List[Int], insideSoFar:Probability)
  
  type DotChart = Array[Array[List[DotEdge]]]
  
  def buildChart(g:Grammar, sent:List[String], lambda:Double) : Chart = {
    val n = sent.size
    
    val chart = ChartHelper.emptyChart(n)
    val dotChartAlmost:DotChart = Array.fill(n, n)(List())
    val dotChartFar:DotChart = Array.fill(n, n)(List())
    
    /// preterminal stuff ///
    for(i <- 0 to n-1){
      println(s"preterminal $i")
      val wordStr = sent(i)
      val word:Word = if(g.voc.allStrings contains wordStr) g.voc(wordStr) else g.unknown
      
      ////// step 1 //////
      
      ////// step 1.1 adding Preterm nodes /////
      val pretermRules = g.pretermRulesForWord(word)
      for(rule @ PretermRule(lhs, word, p) <- pretermRules){
        val newEdge = Edge(i, i, rule, List())
        newEdge.inside = p

        chart(i)(i).putIfAbsent(lhs, new NonTermSpan())
        chart(i)(i).get(lhs).addEdge(newEdge)

        chart(i)(i).get(lhs).inside += newEdge.inside
      }
      
      ////// step 1.2 pruning of Preterm nodes ///// 
      ////// not really necessary
      
      ////// step 2 //////
      
      ////// step 2.1 adding Unarys before preterminals ////
      for(rhs1 <- chart(i)(i).keys()){
        val rhs1Inside = chart(i)(i).get(rhs1).inside
        for(rule @ InnerRule(lhs, rhs, p) <- g.innerUnaryRulesForLeftmostNonTerm(rhs1)){
          val newEdge = Edge(i, i, rule, List())
          newEdge.inside = p*rhs1Inside

          chart(i)(i).putIfAbsent(rule.lhs, new NonTermSpan())
          chart(i)(i).get(lhs).addEdge(newEdge)
          
          chart(i)(i).get(lhs).inside += newEdge.inside
        }
      }

      ////// step 2.3 pruning of Unarys ////
      /////
      
      ////// step 2.4 adding incomplete n-ary dot rules before preterminals////
      val nonTermSpanIterator = chart(i)(i).iterator()
      while(nonTermSpanIterator.hasNext()){
        nonTermSpanIterator.advance()
        val rhs1 = nonTermSpanIterator.key()
        val nonTermSpan = nonTermSpanIterator.value()
      
        for(rule @ InnerRule(lhs, rhs, _) <- g.innerNaryRulesForLeftmostNonTerm(rhs1)){
          val newDotEdge = DotEdge(rhs.tail, rule, List(i+1), nonTermSpan.inside)
          if(rhs.tail.tail.isEmpty){
            dotChartAlmost(i)(i) ::= newDotEdge
          }else{
            dotChartFar(i)(i) ::= newDotEdge
          }
        }
      }
    }
    
    /// inner stuff ///
    for(span <- 2 to n){
      val beamSize = exponentialDecay(g.nonTerms.size, n, span, lambda)
        
      for(i <- 0 until n-span+1){
        val j = i + span - 1
        
        ////// step 1 /////

        ////// step 1.1 completing ALMOST complete edges in real chart /////
        for(split <- i+1 to j){
          println(s"inner $i to $j split $split for full chart")
          for(DotEdge(List(rhsLast), rule @ InnerRule(lhs, rhs, p), splits, insideSoFar) <- dotChartAlmost(i)(split-1)){
            if(chart(split)(j) contains rhsLast){
              /// rule is complete
              /// add it to the real chart
              val newEdge = Edge(i, j, rule, splits.reverse)
              newEdge.inside = insideSoFar*chart(split)(j).get(rhsLast).inside*p

              chart(i)(j).putIfAbsent(lhs, new NonTermSpan())
              chart(i)(j).get(lhs).addEdge(newEdge)
              chart(i)(j).get(lhs).inside += newEdge.inside
            }
          }
        }
        
        ////// step 1.3 pruning of Nary nodes ////
        ////// don't prune ROOT! we need it for glue rules //////
        
        var nonTermSpans = List[(NonTerm, NonTermSpan)]()
        val pruningIt = chart(i)(j).iterator()
        while(pruningIt.hasNext()){
          pruningIt.advance()
          nonTermSpans ::= (pruningIt.key(), pruningIt.value())
        }
        val nonTermsToKeep = nonTermSpans.sortBy{case (nt, nts) => nts.inside.toDouble}.take(beamSize).map{_._1}.toSet
        val pruningIt2 = chart(i)(j).iterator()
        while(pruningIt2.hasNext()){
          pruningIt2.advance()
          val lhs = pruningIt2.key()
          if( (lhs != g.ROOT) && 
              ! (nonTermsToKeep contains lhs)  ){
            pruningIt2.remove()
          }
        }
        
        ////// step 1.4 filling UNARY rules in real chart ////
        for(rhs1 <- chart(i)(j).keys){
          val rhs1Inside = chart(i)(j).get(rhs1).inside
          for(rule @ InnerRule(lhs, rhs, p) <- g.innerUnaryRulesForLeftmostNonTerm(rhs1)){
            val newEdge = Edge(i, j, rule, List())
            newEdge.inside = p*rhs1Inside

            chart(i)(j).putIfAbsent(lhs, new NonTermSpan())
            chart(i)(j).get(lhs).addEdge(newEdge)

            chart(i)(j).get(lhs).inside += newEdge.inside
          }
        }

        ////// step 1.5 pruning of Unarys ////
        ////// In this case not really necessary since the only unary inner rule is ROOT -> whatever /////
        
        ////// step 1.6 completing FAR from complete dot rules ////
        for(split <- i+1 to j){
          println(s"inner $i to $j split $split for dot chart")
          for(DotEdge(incompleteStack, rule @ InnerRule(lhs, rhs, p), splits, insideSoFar) <- dotChartFar(i)(split-1)){
            if(chart(split)(j) contains incompleteStack.head){
              val newInsideSoFar = insideSoFar*chart(split)(j).get(incompleteStack.head).inside
              val newDotEdge = DotEdge(incompleteStack.tail, rule, (j+1)::splits, newInsideSoFar)
              if(incompleteStack.tail.tail.isEmpty){
                dotChartAlmost(i)(j) ::= newDotEdge
              }else{
                dotChartFar(i)(j) ::= newDotEdge
              }
            }
          }
        }
        
        ////// step 2 adding new incomplete nary rules from the grammar ////
        for(rhs1 <- chart(i)(j).keys){
          for(rule @ InnerRule(lhs, rhs, p) <- g.innerNaryRulesForLeftmostNonTerm(rhs1)){
            val rhs1Inside = chart(i)(j).get(rhs.head).inside
            val newDotEdge = DotEdge(rhs.tail, rule, List(j+1), rhs1Inside)
            if(rhs.tail.tail.isEmpty){
              dotChartAlmost(i)(j) ::= newDotEdge
            }else{
              dotChartFar(i)(j) ::= newDotEdge
            }
          }
        }
      }
    }

    chart
  }
  
  @inline
  private def exponentialDecay(maxNumberOfConstituents:Int, n:Int, spanSize:Int, lambda:Double) : Int = {
    val beamSize = maxNumberOfConstituents.toDouble * Math.exp(-lambda * spanSize * n)
    if(beamSize <2)
      2
    else
      beamSize.toInt
  }
  
  def deLatentizeChart(g:Grammar, latentChart:Chart) : Chart = {
    val n = latentChart.size
    val chart = ChartHelper.emptyChart(n)
    
    InsideOutside.inside(latentChart, g)
    InsideOutside.outside(latentChart, g)
    val sentProb:Probability = latentChart(0)(n-1).get(g.ROOT).inside
    
    //Handle PretermRules
    
    for(i <- 0 until n){
      val rs = scala.collection.mutable.Map[(NonTerm, Word), Probability]().withDefaultValue(LogNil)
      val it = latentChart(i)(i).iterator()
      while(it.hasNext()){
        it.advance()
        val nonLatentLhs = removeLatentNotations(g, it.key())
        val nonTermSpan = it.value()
        
        for(edge <- nonTermSpan.edges){
          val word = edge.rule .asInstanceOf[PretermRule].word
          rs((nonLatentLhs, word)) = rs((nonLatentLhs, word)) + nonTermSpan.outside * edge.inside
        }
      }
      
      for(((lhs, word), r) <- rs){
        chart(i)(i).putIfAbsent(lhs, new NonTermSpan())
        val newRule = PretermRule(lhs, word, r/sentProb)
        val newEdge = Edge(i, i, newRule, List())
        chart(i)(i).get(lhs).addEdge(newEdge)
      }
      
    }
    
    //Handle InnerRules

    for(span <- 2 to n){
      for(i <- 0 until n-span+1){
        val j = i + span - 1
        
        val rs = scala.collection.mutable.Map[(NonTerm, List[NonTerm], List[Int]), Probability]().withDefaultValue(LogNil)
        val it = latentChart(i)(j).iterator()
        while(it.hasNext()){
          it.advance()
          val nonLatentLhs = removeLatentNotations(g, it.key())
          val nonTermSpan = it.value()
          
          for(edge <- nonTermSpan.edges){
            val nonLatentRhs = edge.rule.asInstanceOf[InnerRule].rhs.map{removeLatentNotations(g, _)}
            val representation = (nonLatentLhs, nonLatentRhs, edge.splits)
            rs(representation) = rs(representation) + nonTermSpan.outside * edge.inside
          }
        }
        
        for(((lhs, rhs, splits), r) <- rs){
          val newRule = InnerRule(lhs, rhs, r/sentProb)
          val newEdge = Edge(i, j, newRule, splits)
          chart(i)(j).putIfAbsent(lhs, new NonTermSpan())
          chart(i)(j).get(lhs).addEdge(newEdge)
        }
      }
    }
    
    chart
  }
  
  private def removeLatentNotations(g:Grammar, x:NonTerm) : NonTerm = {
    val originalLhs = g.reverseLatentMappings(x)
    val originalLhsStr = g.nonTerms(originalLhs)
    val moreOriginalLhsStr = if(originalLhsStr.matches("^M.C..*")){
                               originalLhsStr.substring(4)
                             }else{
                               originalLhsStr
                             }
    g.nonTerms(moreOriginalLhsStr)
  }
  
  def rebalance(g:Grammar, oldChart:Chart) : Chart = {
    val chart = ChartHelper.copyChart(oldChart)
    val n = chart.size
    
    for(span <- 3 to n){
      for(i <- 0 until n-span+1){
        val j = i + span - 1
        println(s"rebalancing $i $j")
        val ntsIt = chart(i)(j).iterator()
        
        while(ntsIt.hasNext()){
          ntsIt.advance()
          val lhs = ntsIt.key()
          val nts = ntsIt.value()
          
          val perm = g.permutationMappings(lhs)
          
          if(perm == List(1,2) || perm == List(2,1)){
            for(unbalancedUpperEdge @ Edge(_, _, upperRule @ InnerRule(lhs, List(rhs1, rhs2), p3), List(splitPoint), _) <- nts.edges){

              val unbalancedNts = chart(splitPoint)(j).get(rhs2)
              if(g.permutationMappings(rhs2) == perm){
                for(unbalancedLowerEdge @ Edge(_, _, lowerRule @ InnerRule(_, List(rhs21, rhs22), p4), List(subSplitPoint), _) <- unbalancedNts.edges){
                  val p1 = nts.getEdgeProb(InnerRule(lhs, List(rhs2, rhs22), LogNil), List(subSplitPoint))
                  val balancedNts = chart(i)(subSplitPoint-1).get(rhs2)
                  val p2 = if(balancedNts == null){
                    None
                  }else{
                    balancedNts.getEdgeProb(InnerRule(rhs2, List(rhs1, rhs21), LogNil), List(splitPoint))
                  }
                  if(p1.isDefined && p2.isDefined){
                    // we can do the balancing now
                    val p1Val = p1.get
                    val p2Val = p2.get

                    // remove bad edge
                    nts.removeEdge(unbalancedUpperEdge)
                    val newProb = p1Val + p3*p4/p2Val
                    val newEdge = Edge(i, j, InnerRule(lhs, List(rhs2, rhs22), newProb), List(subSplitPoint))
                    // remove edge that we are going to update
                    nts.removeEdge(newEdge)
                    // add edge that we are updating
                    nts.addEdge(newEdge)
                  }
                }

              }
            }
          }
        }

      }
    }
    
    chart
  }

}
