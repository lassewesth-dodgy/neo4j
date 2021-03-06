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
package org.neo4j.cypher.internal.compiler.v2_1.pipes

import org.neo4j.cypher.internal.compiler.v2_1._
import commands.expressions.{Literal, Multiply, Expression, Identifier}
import symbols._
import org.junit.Test
import org.scalatest.Assertions
import org.scalatest.mock.MockitoSugar

class DistinctPipeTest extends Assertions with MockitoSugar {

  private implicit val monitor = mock[PipeMonitor]

  @Test def distinct_input_passes_through() {
    //GIVEN
    val pipe = createDistinctPipe(List(Map("x" -> 1), Map("x" -> 2)))

    //WHEN
    val result = pipe.createResults(QueryStateHelper.empty)

    //THEN
    assert(result.toList === List(Map("x" -> 1), Map("x" -> 2)))
  }

  @Test def distinct_executes_expressions() {
    //GIVEN
    val expressions = Map("doubled" -> Multiply(Identifier("x"), Literal(2)))
    val pipe = createDistinctPipe(List(Map("x" -> 1), Map("x" -> 2)), expressions)

    //WHEN
    val result = pipe.createResults(QueryStateHelper.empty)

    //THEN
    assert(result.toList === List(Map("doubled" -> 2), Map("doubled" -> 4)))
  }

  @Test def undistinct_input_passes_through() {
    //GIVEN
    val pipe = createDistinctPipe(List(Map("x" -> 1), Map("x" -> 1)))

    //WHEN
    val result = pipe.createResults(QueryStateHelper.empty)

    //THEN
    assert(result.toList === List(Map("x" -> 1)))
  }

  def createDistinctPipe(input: List[Map[String, Int]], expressions: Map[String, Expression] = Map("x" -> Identifier("x"))) = {
    val source = new FakePipe(input, "x" -> CTNumber)
    new DistinctPipe(source, expressions)
  }
}
