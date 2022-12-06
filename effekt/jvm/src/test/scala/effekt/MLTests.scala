package effekt

import java.io.File

import sbt.io._
import sbt.io.syntax._

import scala.language.implicitConversions

class MLTests extends EffektTests {

  override lazy val included: List[File] = List(examplesDir)

  override lazy val ignored: List[File] = List(
    // Box is not supported
    examplesDir / "pos" / "capture" / "defdef.effekt",
    examplesDir / "pos" / "capture" / "mbed.effekt",
    examplesDir / "pos" / "capture" / "optimizing_unbox.effekt",
    examplesDir / "pos" / "capture" / "regions.effekt",
    examplesDir / "pos" / "capture" / "resources.effekt",
    examplesDir / "pos" / "capture" / "selfregion.effekt",
    examplesDir / "pos" / "infer" / "infer_boxed.effekt",
    examplesDir / "pos" / "infer" / "infer_effect_polymorphic.effekt",
    examplesDir / "pos" / "issue108.effekt",
    examplesDir / "pos" / "lambdas" / "annotated.effekt",
    examplesDir / "pos" / "lambdas" / "effects.effekt",
    examplesDir / "pos" / "lambdas" / "generators.effekt",
    examplesDir / "pos" / "lambdas" / "higherOrder.effekt",
    examplesDir / "pos" / "lambdas" / "localState.effekt",
    examplesDir / "pos" / "lambdas" / "scheduler.effekt",
    examplesDir / "pos" / "lambdas" / "simpleclosure.effekt",

    // polymorphic effect operation not supported
    examplesDir / "pos" / "existentials.effekt",
    examplesDir / "pos" / "triples.effekt",

    // heap
    examplesDir / "casestudies" / "ad.md",

    // regex
    examplesDir / "casestudies" / "anf.md",
    examplesDir / "casestudies" / "lexer.md",
    examplesDir / "casestudies" / "parser.md",
    examplesDir / "casestudies" / "prettyprinter.md",
    examplesDir / "pos" / "simpleparser.effekt",

    // cslist
    examplesDir / "chez" / "libraries.effekt",

    // cont
    examplesDir / "pos" / "unsafe_cont.effekt",
    examplesDir / "pos" / "propagators.effekt",

    // array api
    examplesDir / "pos" / "raytracer.effekt",

    // async
    examplesDir / "pos" / "io" / "async_file_io.effekt",

    // mut map
    examplesDir / "pos" / "maps.effekt",

    // Difficult fix
    examplesDir / "pos" / "nim.effekt",
    examplesDir / "pos" / "multieffects.effekt",
  )

  def runTestFor(input: java.io.File, check: File, expected: String): Unit =
    test(input.getPath + " (ml)") {
      val out = runML(input)
      assertNoDiff(out, expected)
    }

  def runML(input: File): String = {
    val compiler = new effekt.Driver {}
    val configs = compiler.createConfig(Seq(
      "--Koutput", "string",
      "--backend", "ml"
    ))
    configs.verify()
    compiler.compileFile(input.getPath, configs)
    configs.stringEmitter.result()
  }
}
