package edu.nus.mrepair.klee

import edu.nus.mrepair._
import edu.nus.mrepair.AngelicFix._
import edu.nus.mrepair.Utils._
import java.io.File
import edu.nus.mrepair.Utils.SimpleLogger._
import org.smtlib.IExpr
import scala.util.parsing.combinator._
import com.microsoft.z3._
import edu.nus.maxsmtplay._
import scala.collection.JavaConverters._
import edu.nus.mrepair.synthesis.{ProgramFormula, Formula, ComponentFormula}
import Formula._
import ProgramFormula._


/**
  * AngelicFix implementation top class
  */
object AFRepair {

  def generatePatch(synthesisConfig: SynthesisConfig,
                    extracted: File,
                    angelicForest: AngelicForest): Map[Int, String] = {
    ???
  }

  def debugMode(): Boolean = {
    sys.env.get("AF_DEBUG") match {
      case None => false
      case _ => true
    }
  }

  def getAvailableSymbols(formula: String, solver: MaxSMT with Z3): List[String] = {
    val origClauses = solver.z3.parseSMTLIB2String(formula + "\n(check-sat)\n(exit)", Array(), Array(), Array(), Array())
    solver.solveAndGetModel(Nil, origClauses :: Nil) match {
      case Some((_, origModel)) =>
        origModel.getConstDecls().toList.map({ case d => d.getName().toString})
      case None =>
        println("UNSAT")
        Nil
    }
  }

  /*
   Name format example:

   af!suspicious!int!0 = 3
   af!suspicious!int!0!env!x = 1
   af!input!int!y = 3
   af!output!int!stdout = 4
   af!suspicious!int!0!env!y = 3
   af!input!int!x = 1
   af!suspicious!int!0!original = 4
  */
  def getAngelicPath(assignment: VariableValues): AngelicPath = {
    val suspiciousPrefix = "af!suspicious!int!"
    assignment.toList.filter({
      case (n, v) => n.startsWith(suspiciousPrefix)
    }).map({
      case (n, v) =>
        val id :: rest = n.drop(suspiciousPrefix.length).split("!").toList
        (id.toInt, (rest, v))
    }).groupBy(_._1).toList.map({
      case (id, assignment) =>
        var original: Option[Int] = None
        var angelic: Option[Int] = None
        val context = assignment.foldLeft(List[(ProgramVariable, Int)]())({
          case (acc, (_, ("original" :: Nil, v))) =>
            original = Some(v)
            acc
          case (acc, (_, ("angelic" :: Nil, v))) =>
            angelic = Some(v)
            acc
          case (acc, (_, ("env" :: name :: Nil, v))) =>
            (ProgramVariable(name, IntegerType()), v) :: acc
        })
        AngelicValue(context, angelic.get, id)
    })
  }

  /**
    * Solving smt files generated by KLEE for each test case
    */
  def generateAngelicForest(smtFiles: List[String], testUniverseDir: String, testSuiteIds: List[String]): AngelicForest = {
    val af = scala.collection.mutable.Map[String, List[AngelicPath]]()
    testSuiteIds.foreach({ case id => af += id -> Nil })

    val solver = new Sat() with Z3
    solver.init(None)

    var repairedTests: List[String] = Nil

    smtFiles.map({
      case file =>
        if (debugMode()) println("[synthesis] checking path " + file)
        val formula = scala.io.Source.fromFile(file).mkString
        val afVars = getAvailableSymbols(formula, solver)
        solver.solver.reset()

        if (! afVars.isEmpty) {

          val getter = afVars.map({
            case varName =>
              s"(declare-fun aux_$varName () (_ BitVec 32)) (assert (= aux_$varName (concat (select $varName (_ bv3 32)) (concat (select $varName (_ bv2 32)) (concat (select $varName (_ bv1 32)) (select $varName (_ bv0 32)))))))"
          }).mkString(" ")

          testSuiteIds.foreach({
            case testId =>

              if (debugMode()) println("[synthesis] checking test " + testId)
              val (in, out) = getTestData(testUniverseDir, testId)

              val inputAssertion = in.map({
                case (name, value) =>
                  val varName = "af!input!int!" + name
                  if(afVars.contains(varName)) {
                    s"(assert (= ((_ int2bv 32) $value) (concat (select $varName (_ bv3 32)) (concat (select $varName (_ bv2 32)) (concat (select $varName (_ bv1 32)) (select $varName (_ bv0 32)))))))"
                  } else {
                    ""
                  }
              }).mkString("\n")

              var unconstraintOutputVars: List[String] = Nil;

              val outputAssertion = out.map({
                case (name, value) =>
                  val varName = "af!output!int!" + name
                  if(afVars.contains(varName)) {
                    s"(assert (= ((_ int2bv 32) $value) (concat (select $varName (_ bv3 32)) (concat (select $varName (_ bv2 32)) (concat (select $varName (_ bv1 32)) (select $varName (_ bv0 32)))))))"
                  } else {
                    unconstraintOutputVars = name :: unconstraintOutputVars;
                    ""
                  }
              }).mkString("\n")

              if (!unconstraintOutputVars.isEmpty) {
                if (debugMode()) {
                  println("[synthesis] unconstraint output variables: " + unconstraintOutputVars.mkString(" "))
                }
              } else {
                val ending = "\n(check-sat)\n(exit)"
                val clauses = solver.z3.parseSMTLIB2String(formula + inputAssertion + outputAssertion + getter + ending, Array(), Array(), Array(), Array())
                solver.solveAndGetModel(Nil, clauses :: Nil) match {
                  case Some((_, model)) =>
                    repairedTests = testId :: repairedTests
                    val result = afVars.map({
                      case varName =>
                        val auxName = "aux_" + varName
                        val raw_value = model.eval(solver.z3.mkBVConst(auxName, 32), false)
                        val value =
                          try {
                            raw_value.asInstanceOf[BitVecNum].getInt
                          } catch {
                            case _ =>
                              val v = raw_value.toString.toLong - 4294967296L
                              if (debugMode()) println("[synthesis] evaluating " + varName + " value " + raw_value + " as " + v)
                              v
                          }
                        (varName, value)
                    })
                    if (debugMode()) result.foreach({ case (n, v) => println("[synthesis] " + n + " = " + v)})
                    val ap = getAngelicPath(result.map({ case (n, v) => (n, v.toInt) }).toMap)
                    af(testId) = ap :: af(testId)
                  case None => if (debugMode()) println("[synthesis] UNSAT")
                }
              }
              solver.solver.reset()
          })
        }
    })
    solver.delete()

    println("Paths explored: " + smtFiles.length)
    println("Angelic values generated: " + repairedTests.length)
    println("Test cases repaired: " + repairedTests.distinct.length + "/" + testSuiteIds.length)

    af.toList.toMap
  }


  def getTestData(testUniverseDir: String, testId: String): (VariableValues, VariableValues) = {
    val inputsFile = testUniverseDir + File.separator + testId + ".in"
    val outputsFile = testUniverseDir + File.separator + testId + ".out"
    val inputsSource = scala.io.Source.fromFile(inputsFile)
    val outputsSource = scala.io.Source.fromFile(outputsFile)
    val inputsContent = inputsSource.mkString
    val outputsContent = outputsSource.mkString
    val in = TestMappingParser(inputsContent)
    val out = TestMappingParser(outputsContent)
    inputsSource.close()
    outputsSource.close()
    (in, out)
  }

  type VariableValues = Map[String, Int]

  object TestMappingParser extends JavaTokenParsers {
    def apply(input: String): VariableValues = parseAll(inputs, input).get.toMap
    def inputs = rep(input)
    def input = inputName ~ "=" ~ inputValue ^^ { case n ~ "=" ~ v => (n, v) }
    def inputName: Parser[String] = ident
    def inputValue: Parser[Int] = wholeNumber ^^ { case si => Integer.parseInt(si) }
  }

}
