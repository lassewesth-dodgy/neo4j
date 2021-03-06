/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.ast

import org.neo4j.cypher.internal.compiler.v2_1._
import symbols._
import org.neo4j.cypher.internal.compiler.v2_1.commands.expressions.StringHelper.RichString

sealed trait Clause extends ASTNode with SemanticCheckable {
  def name: String
}

sealed trait UpdateClause extends Clause

sealed trait ClosingClause extends Clause {
  def distinct: Boolean
  def returnItems: ReturnItems
  def orderBy: Option[OrderBy]
  def skip: Option[Skip]
  def limit: Option[Limit]

  def semanticCheck =
    returnItems.semanticCheck then
    checkSortItems then
    checkSkipLimit

  // use a scoped state containing the aliased return items for the sort expressions
  private def checkSortItems: SemanticCheck = s => {
    val result = (returnItems.declareIdentifiers(s) then orderBy.semanticCheck)(s.newScope)
    SemanticCheckResult(result.state.popScope, result.errors)
  }

  // use an empty state when checking skip & limit, as these have isolated scope
  private def checkSkipLimit: SemanticState => Seq[SemanticError] =
    s => (skip ++ limit).semanticCheck(SemanticState.clean).errors
}

case class LoadCSV(withHeaders: Boolean, urlString: Expression, identifier: Identifier, fieldTerminator: Option[StringLiteral])(val position: InputPosition) extends Clause with SemanticChecking {
  val name = "LOAD CSV"

  def semanticCheck: SemanticCheck =
    urlString.semanticCheck(Expression.SemanticContext.Simple) then
    urlString.expectType(CTString.covariant) then
    checkFieldTerminator then
    typeCheck

  private def checkFieldTerminator: SemanticCheck = {
    fieldTerminator match {
      case Some(literal) if literal.value.length != 1 =>
        SemanticError("CSV field terminator can only be one character wide", literal.position)
      case _ => SemanticCheckResult.success
    }
  }

  private def typeCheck: SemanticCheck = {
    val typ = if (withHeaders)
      CTMap
    else
      CTCollection(CTString)

    identifier.declare(typ)
  }
}

case class Start(items: Seq[StartItem], where: Option[Where])(val position: InputPosition) extends Clause {
  val name = "START"

  def semanticCheck = items.semanticCheck then where.semanticCheck
}


case class Match(optional: Boolean, pattern: Pattern, hints: Seq[Hint], where: Option[Where])(val position: InputPosition) extends Clause with SemanticChecking {
  def name = "MATCH"

  def semanticCheck =
    pattern.semanticCheck(Pattern.SemanticContext.Match) then
    hints.semanticCheck then
    where.semanticCheck then
    checkHints

  def checkHints: SemanticCheck = {
    val error: Option[SemanticCheck] = hints.collectFirst {
      case hint@UsingIndexHint(Identifier(identifier), LabelName(labelName), Identifier(property))
        if !containsLabelPredicate(identifier, labelName)
          || !containsPropertyPredicate(identifier, property) =>
        SemanticError(
          """|Cannot use index hint in this context.
             | Index hints require using a simple equality comparison in WHERE (either directly or as part of a
             | top-level AND).
             | Note that the label and property comparison must be specified on a
             | non-optional node""".stripLinesAndMargins, hint.position)
      case hint@UsingScanHint(Identifier(identifier), LabelName(labelName))
        if !containsLabelPredicate(identifier, labelName) =>
        SemanticError(
          """|Cannot use label scan hint in this context.
             | Label scan hints require using a simple label test in WHERE (either directly or as part of a
             | top-level AND). Note that the label must be specified on a non-optional node""".stripLinesAndMargins, hint.position)
    }
    error.getOrElse(SemanticCheckResult.success)
  }

  def containsPropertyPredicate(identifier: String, property: String): Boolean = {
    val properties: Seq[String] = where match {
      case Some(where) => where.treeFold(Seq.empty[String]) {
        case Equals(Property(Identifier(identifier), PropertyKeyName(name)), _) =>
          (acc, _) => acc :+ name
        case Equals(_, Property(Identifier(identifier), PropertyKeyName(name))) =>
          (acc, _) => acc :+ name
        case _: Where | _: And =>
          (acc, children) => children(acc)
        case _ =>
          (acc, _) => acc
      }
      case None => Seq.empty
    }
    properties.contains(property)
  }

  def containsLabelPredicate(identifier: String, label: String): Boolean = {
    var labels = pattern.fold(Seq.empty[String]) {
      case NodePattern(Some(Identifier(identifier)), labels, _, _) =>
        list => list ++ labels.map(_.name)
    }
    labels = where match {
      case Some(where) => where.treeFold(labels) {
        case HasLabels(Identifier(identifier), labels) =>
          (acc, _) => acc ++ labels.map(_.name)
        case _: Where | _: And =>
          (acc, children) => children(acc)
        case _ =>
          (acc, _) => acc
      }
      case None => labels
    }
    labels.contains(label)
  }
}

case class Merge(pattern: Pattern, actions: Seq[MergeAction])(val position: InputPosition) extends UpdateClause {
  def name = "MERGE"

  def semanticCheck =
    pattern.semanticCheck(Pattern.SemanticContext.Merge) then
    actions.semanticCheck
}

case class Create(pattern: Pattern)(val position: InputPosition) extends UpdateClause {
  def name = "CREATE"

  def semanticCheck = pattern.semanticCheck(Pattern.SemanticContext.Create)
}

case class CreateUnique(pattern: Pattern)(val position: InputPosition) extends UpdateClause {
  def name = "CREATE UNIQUE"

  def semanticCheck = pattern.semanticCheck(Pattern.SemanticContext.CreateUnique)
}

case class SetClause(items: Seq[SetItem])(val position: InputPosition) extends UpdateClause {
  def name = "SET"

  def semanticCheck = items.semanticCheck
}

case class Delete(expressions: Seq[Expression])(val position: InputPosition) extends UpdateClause {
  def name = "DELETE"

  def semanticCheck =
    expressions.semanticCheck(Expression.SemanticContext.Simple) then
    warnAboutDeletingLabels then
    expressions.expectType(CTNode.covariant | CTRelationship.covariant | CTPath.covariant)

  def warnAboutDeletingLabels =
    expressions.filter(_.isInstanceOf[HasLabels]) map {
      e => SemanticError("DELETE doesn't support removing labels from a node. Try REMOVE.", e.position)
    }
}

case class Remove(items: Seq[RemoveItem])(val position: InputPosition) extends UpdateClause {
  def name = "REMOVE"

  def semanticCheck = items.semanticCheck
}

case class Foreach(identifier: Identifier, expression: Expression, updates: Seq[Clause])(val position: InputPosition) extends UpdateClause with SemanticChecking {
  def name = "FOREACH"

  def semanticCheck =
    expression.semanticCheck(Expression.SemanticContext.Simple) then
    expression.expectType(CTCollection(CTAny).covariant) then
    updates.filter(!_.isInstanceOf[UpdateClause]).map(c => SemanticError(s"Invalid use of ${c.name} inside FOREACH", c.position)) ifOkThen
    withScopedState {
      val possibleInnerTypes: TypeGenerator = expression.types(_).unwrapCollections
      identifier.declare(possibleInnerTypes) then updates.semanticCheck
    }
}

case class With(
    distinct: Boolean,
    returnItems: ReturnItems,
    orderBy: Option[OrderBy],
    skip: Option[Skip],
    limit: Option[Limit],
    where: Option[Where])(val position: InputPosition) extends ClosingClause
{
  def name = "WITH"

  override def semanticCheck =
    super.semanticCheck then
    checkAliasedReturnItems

  private def checkAliasedReturnItems: SemanticState => Seq[SemanticError] = state => returnItems match {
    case li: ListedReturnItems => li.items.filter(!_.alias.isDefined).map(i => SemanticError("Expression in WITH must be aliased (use AS)", i.position))
    case _                     => Seq()
  }
}

case class Return(
    distinct: Boolean,
    returnItems: ReturnItems,
    orderBy: Option[OrderBy],
    skip: Option[Skip],
    limit: Option[Limit])(val position: InputPosition) extends ClosingClause {
  def name = "RETURN"
}

case class PeriodicCommitHint(size: Option[IntegerLiteral])(val position: InputPosition) extends ASTNode with SemanticCheckable {
  def name = s"USING PERIODIC COMMIT $size"

  override def semanticCheck: SemanticCheck = size match {
    case Some(integer) if integer.value <= 0 =>
      SemanticError(s"Commit size error - expected positive value larger than zero, got ${integer.value}", integer.position)
    case _ =>
      SemanticCheckResult.success
  }
}
