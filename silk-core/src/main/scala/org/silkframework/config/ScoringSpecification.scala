package org.silkframework.config

import org.silkframework.entity.rdf.SparqlEntitySchema
import org.silkframework.rule.{ScoringRule, TransformRule}
import org.silkframework.util.Identifier

/**
 * This class contains all the required parameters to execute a scoring task.
 */
case class ScoringSpecification(id: Identifier = Identifier.random, selection: DatasetSelection, rules: Seq[ScoringRule], outputs: Seq[Identifier] = Seq.empty) {

  def entityDescription = {
    new SparqlEntitySchema(
      variable = selection.variable,
      restrictions = selection.restriction,
      paths = rules.flatMap(_.toTransform.paths).distinct.toIndexedSeq
    )
  }

}

object ScoringSpecification {

  def fromTransform(task: TransformSpecification) =
    ScoringSpecification(
      id = task.id,
      selection = task.selection,
      rules = task.rules.map(ScoringRule.fromTransform),
      outputs = task.outputs
    )

}