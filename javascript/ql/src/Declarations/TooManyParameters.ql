/**
 * @name Function with too many parameters
 * @description Functions with many parameters are hard to read and hard to use.
 * @kind problem
 * @problem.severity recommendation
 * @id js/too-many-parameters
 * @tags testability
 *       readability
 * @precision high
 */

import javascript
import semmle.javascript.RestrictedLocations

from Function f
where
  not f.inExternsFile() and
  f.getNumParameter() > 7 and
  // exclude AMD modules
  not exists(AMDModuleDefinition m | f = m.getFactoryNode().(DataFlow::FunctionNode).getAstNode())
select f.(FirstLineOf),
  capitalize(f.describe()) + " has too many parameters (" + f.getNumParameter() + ")."
