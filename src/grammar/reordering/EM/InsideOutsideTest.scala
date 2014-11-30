package grammar.reordering.EM

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import grammar.reordering.representation.IntMapping
import grammar.reordering.representation.Rule
import grammar.reordering.representation.NonTerm
import grammar.reordering.representation.Grammar
import grammar.reordering.representation.`package`.InnerRule
import grammar.reordering.representation.`package`.PretermRule
import grammar.reordering.representation.Probability

class InsideOutsideTest extends FlatSpec with ShouldMatchers{

  "initial iteration" should "not break" in {
    val alignment = "1-1 2-0 3-0 4-0"
    val sent = "Nekakva recenica koja nema mnogo smisla"
    
    val g = InsideOutside.initialIteration(List(sent) zip List(alignment))
    // println(g)
  }

  "real iteration" should "not break" in {
    val alignment = "1-1 2-0 3-0 4-0"
    val sent = "Nekakva recenica koja nema mnogo smisla"

    val g = InsideOutside.initialIteration(List(sent) zip List(alignment))
    
    val batchSize = 4
    val threads = 2
    val (g2, likelihood) = InsideOutside.iteration(List((sent, alignment)), g, batchSize, threads)
    // println(g2)
  }

  "real 5 iterations" should "not break" in {
    val alignment = "1-1 2-0 3-0 4-0"
    val sent = "Nekakva recenica koja nema mnogo smisla"

    val g0 = InsideOutside.initialIteration(List(sent) zip List(alignment))
    val rules:Set[Rule] = g0.allRules.map{
      case InnerRule(lhs, rhs, prob) => InnerRule(lhs, rhs, Probability(0.01))
      case PretermRule(lhs, word, prob) => PretermRule(lhs, word, Probability(0.01))
    }.toSet
    // val g1 = g0.copyConstructor(rules)
    val g1 = new Grammar(rules, g0.latentMappings, g0.voc, g0.nonTerms)
    
    val batchSize = 4
    val threads = 2
    println("GRAMMAR 1")
    // println(g1)

    var g = g1
    var likelihood = 0.0
    
    for(i <- 2 to 6){
      val res = InsideOutside.iteration(List((sent, alignment)), g, batchSize, threads)
      g = res._1
      val improvement = Math.exp(res._2) - Math.exp(likelihood)
      likelihood = res._2
      println(s"GRAMMAR $i\t"+Math.exp(likelihood))
      println(s"improvement "+improvement)
      println(g)
    }
  }

}
