package wdl4s

import org.scalatest.{FlatSpec, Matchers}
import wdl4s.expression.NoFunctions
import wdl4s.values._

import scala.util.Try

class AdvancedInterpolationSpec extends FlatSpec with Matchers {
  val wdl =
    """
      |task test {
      |  String eval_this
      |  String var="inside"
      |  String evaled="${eval_this}"
      |
      |  command {
      |    echo '${eval_this} ${evaled} ${var}'
      |  }
      |  output {
      |    String out = read_string(stdout())
      |  }
      |}
      |
      |workflow testWF {
      |  String var = "outside"
      |  call test
      |  call test as test2 {input: eval_this="${var}"}
      |}
    """.stripMargin

  val namespace = WdlNamespaceWithWorkflow.load(wdl)

  it should "be able to resolve interpolated strings within interpolated strings" in {
    val testCall = namespace.workflow.calls.find(_.unqualifiedName == "test") getOrElse {
      fail("call 'test' not found")
    }
    val testCmd = testCall.instantiateCommandLine(Map("testWF.test.eval_this" -> WdlString("${var}")), NoFunctions)
    testCmd shouldEqual Try("echo 'inside inside inside'")
  }

  it should "be able to resolve interpolated strings within interpolated strings (2)" in {
    val test2Call = namespace.workflow.calls.find(_.unqualifiedName == "test2") getOrElse {
      fail("call 'test2' not found")
    }
    val test2Cmd = test2Call.instantiateCommandLine(Map(), NoFunctions)
    test2Cmd shouldEqual Try("echo 'outside outside inside'")
  }
}
