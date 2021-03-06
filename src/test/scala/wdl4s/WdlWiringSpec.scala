package wdl4s

import better.files._
import org.scalatest.{Matchers, FlatSpec}
import spray.json._

import scala.collection.immutable.ListMap

class WdlWiringSpec extends FlatSpec with Matchers {
  val testCases = File("src/test/cases")
  testCases.createDirectories()

  testCases.list.toSeq.filter(_.isDirectory) foreach { testDir =>
    val wdlFile = testDir / "test.wdl"
    if (!wdlFile.exists) fail(s"Expecting a 'test.wdl' file at ${testDir.name}")
    def resolver(relPath: String): String = (testDir / relPath).contentAsString
    val namespace = WdlNamespaceWithWorkflow.load(wdlFile.toJava, resolver _)
    val wdlFileRelPath = File(".").relativize(wdlFile)

    expectedFullyQualifiedNames(testDir, namespace) foreach { case (fqn, expectedType) =>
      it should s"resolve FQN $fqn to object of type $expectedType in WDL file $wdlFileRelPath" in {
        val resolution = namespace.resolve(fqn)
        resolution.map(_.getClass.getSimpleName) shouldEqual Option(expectedType)
        resolution.map(_.fullyQualifiedName) shouldEqual Option(fqn)
      }
    }

    expectedInputs(testDir, namespace) foreach { case (fqn, wdlType) =>
      it should s"have $fqn (of type $wdlType) as an input in WDL file $wdlFileRelPath" in {
        val input = namespace.workflow.inputs.get(fqn)
        input should not be None
        input.map(_.wdlType.toWdlString) shouldEqual Option(wdlType)
      }
    }

    expectedFullyQualifiedNamesWithIndexScopes(testDir, namespace) foreach { case (fqn, expectedType) =>
      it should s"resolve FQN (with index scopes) $fqn to object of type $expectedType in WDL file $wdlFileRelPath" in {
        val resolution = namespace.resolve(fqn)
        resolution.map(_.getClass.getSimpleName) shouldEqual Option(expectedType)
        resolution.map(_.fullyQualifiedNameWithIndexScopes) shouldEqual Option(fqn)
      }
    }

    expectedParents(testDir, namespace) foreach { case (nodeFqn, parentFqn) =>
      it should s"compute parent of $nodeFqn to be $parentFqn in WDL file $wdlFileRelPath" in {
        val nodeResolution = namespace.resolve(nodeFqn)
        val parentResolution = parentFqn.flatMap(namespace.resolve)
        nodeResolution.flatMap(_.parent) shouldEqual parentResolution
      }
    }

    expectedChildren(testDir, namespace) foreach { case (nodeFqn, children) =>
      it should s"compute parent of $nodeFqn to be ${children.map(_.fullyQualifiedName).mkString(", ")} in WDL file $wdlFileRelPath" in {
        val nodeResolution = namespace.resolve(nodeFqn)
        nodeResolution.map(_.children) shouldEqual Option(children)
      }
    }

    expectedUpstream(testDir, namespace) foreach { case (node, expectedUpstreamNodes) =>
      it should s"compute upstream nodes for FQN ${node.fullyQualifiedName} in WDL file $wdlFileRelPath" in {
        node.upstream shouldEqual expectedUpstreamNodes
      }
    }

    expectedDownstream(testDir, namespace) foreach { case (node, expectedDownstreamNodes) =>
      it should s"compute downstream nodes for FQN ${node.fullyQualifiedName} in WDL file $wdlFileRelPath" in {
        node.downstream shouldEqual expectedDownstreamNodes
      }
    }

    expectedAncestry(testDir, namespace) foreach { case (node, expectedAncestry) =>
      it should s"compute ancestry for FQN ${node.fullyQualifiedName} in WDL file $wdlFileRelPath" in {
        node.ancestry shouldEqual expectedAncestry
      }
    }
  }

  private def expectedInputs(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[FullyQualifiedName, String] = {
    val expectedWorkflowInputsFile = testDir / "inputs.json"

    if (!expectedWorkflowInputsFile.exists) {
      val workflowInputs = namespace.workflow.inputs map { case (fqn, input) =>
        fqn -> JsString(input.wdlType.toWdlString)
      }
      val jsObject = JsObject(ListMap(workflowInputs.toSeq.sortBy(_._1): _*))
      expectedWorkflowInputsFile.write(jsObject.prettyPrint)
    }

    expectedWorkflowInputsFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsString]] map {
      case (k, v) => k -> v.value
    }
  }

  private def expectedParents(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[FullyQualifiedName, Option[FullyQualifiedName]] = {
    val expectedParentsFile = testDir / "parents.json"

    if (!expectedParentsFile.exists) {
      val fqnsAndParent = namespace.descendants map { scope =>
        scope.fullyQualifiedName -> scope.parent.map(_.fullyQualifiedName).map(JsString(_)).getOrElse(JsNull)
      }
      val jsObject = JsObject(ListMap(fqnsAndParent.toSeq.sortBy(_._1): _*))
      expectedParentsFile.write(jsObject.prettyPrint)
    }

    expectedParentsFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsValue]] map {
      case (k, v: JsString) => k -> Option(v.value)
      case (k, v) => k -> None
    }
  }

  private def expectedChildren(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[FullyQualifiedName, Seq[Scope]] = {
    val expectedChildrenFile = testDir / "children.json"

    if (!expectedChildrenFile.exists) {
      val fqnsAndChildren = namespace.descendants map { scope =>
        scope.fullyQualifiedName -> JsArray(scope.children.map(_.fullyQualifiedName).map(JsString(_)).toVector)
      }
      val jsObject = JsObject(ListMap(fqnsAndChildren.toSeq.sortBy(_._1): _*))
      expectedChildrenFile.write(jsObject.prettyPrint)
    }

    expectedChildrenFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsArray]] map {
      case (k, v) =>
        val children = v.elements.collect({ case s: JsString => s }).map(s => namespace.resolve(s.value).get)
        k -> children
    }
  }

  private def expectedFullyQualifiedNames(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[FullyQualifiedName, String] = {
    val expectedFqnsAndClassFile = testDir / "fqn.json"

    if (!expectedFqnsAndClassFile.exists) {
      val fqnsAndClassType = namespace.descendants map { scope =>
        scope.fullyQualifiedName -> JsString(scope.getClass.getSimpleName)
      }
      val jsObject = JsObject(ListMap(fqnsAndClassType.toSeq.sortBy(_._1): _*))
      expectedFqnsAndClassFile.write(jsObject.prettyPrint)
    }

    expectedFqnsAndClassFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsString]] map {
      case (k, v) => k -> v.value
    }
  }

  private def expectedFullyQualifiedNamesWithIndexScopes(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[FullyQualifiedName, String] = {
    val expectedFqnsAndClassFile = testDir / "fqn_index_scopes.json"

    if (!expectedFqnsAndClassFile.exists) {
      val fqnsAndClassType = namespace.descendants map { scope =>
        scope.fullyQualifiedNameWithIndexScopes -> JsString(scope.getClass.getSimpleName)
      }
      val jsObject = JsObject(ListMap(fqnsAndClassType.toSeq.sortBy(_._1): _*))
      expectedFqnsAndClassFile.write(jsObject.prettyPrint)
    }

    expectedFqnsAndClassFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsString]] map {
      case (k, v) => k -> v.value
    }
  }

  private def expectedAncestry(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[Scope, Seq[Scope]] = {
    val expectedAncestryFile = testDir / "ancestry.json"

    if (!expectedAncestryFile.exists) {
      val ancestryFqns = namespace.descendants map { scope =>
        scope.fullyQualifiedName -> JsArray(scope.ancestry.toVector.map(_.fullyQualifiedName).map(JsString(_)))
      }
      val jsObject = JsObject(ListMap(ancestryFqns.toSeq.sortBy(_._1): _*))
      expectedAncestryFile.write(jsObject.prettyPrint)
    }

    expectedAncestryFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsArray]] map {
      case (k, v) =>
        val expectedAncestry = v.elements.asInstanceOf[Vector[JsString]].map(n => namespace.resolve(n.value).get)
        val resolvedFqn = namespace.resolve(k).get.asInstanceOf[Scope with GraphNode]
        resolvedFqn -> expectedAncestry
    }
  }

  private def expectedUpstream(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[Scope with GraphNode, Set[Scope]] = {
    val expectedUpstreamFile = testDir / "upstream.json"

    if (!expectedUpstreamFile.exists) {
      val upstreamFqns = namespace.descendants.collect({ case n: Scope with GraphNode => n }) map { node =>
        node.fullyQualifiedName -> JsArray(node.upstream.toVector.map(_.fullyQualifiedName).sorted.map(JsString(_)))
      }
      val jsObject = JsObject(ListMap(upstreamFqns.toSeq.sortBy(_._1): _*))
      expectedUpstreamFile.write(jsObject.prettyPrint)
    }

    expectedUpstreamFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsArray]] map {
      case (k, v) =>
        val expectedUpstream = v.elements.asInstanceOf[Vector[JsString]].map(n => namespace.resolve(n.value).get).toSet
        val resolvedFqn = namespace.resolve(k).get.asInstanceOf[Scope with GraphNode]
        resolvedFqn -> expectedUpstream
    }
  }

  private def expectedDownstream(testDir: File, namespace: WdlNamespaceWithWorkflow): Map[Scope with GraphNode, Set[Scope]] = {
    val expectedDownstreamFile = testDir / "downstream.json"

    if (!expectedDownstreamFile.exists) {
      val downstreamFqns = namespace.descendants.collect({ case n: Scope with GraphNode => n }) map { node =>
        node.fullyQualifiedName -> JsArray(node.downstream.toVector.map(_.fullyQualifiedName).sorted.map(JsString(_)))
      }
      val jsObject = JsObject(ListMap(downstreamFqns.toSeq.sortBy(_._1): _*))
      expectedDownstreamFile.write(jsObject.prettyPrint)
    }

    expectedDownstreamFile.contentAsString.parseJson.asInstanceOf[JsObject].fields.asInstanceOf[Map[String, JsArray]] map { case (k, v) =>
      val expectedDownstream = v.elements.asInstanceOf[Vector[JsString]].map(n => namespace.resolve(n.value).get).toSet
      val resolvedFqn = namespace.resolve(k).get.asInstanceOf[Scope with GraphNode]
      resolvedFqn -> expectedDownstream
    }
  }
}