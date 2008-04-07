// Copyright (C) 2008 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.caja.parser.quasiliteral;

import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ForEachLoop;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.NumberLiteral;
import com.google.caja.parser.js.Operation;
import com.google.caja.parser.js.Operator;
import com.google.caja.parser.js.Reference;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.plugin.ReservedNames;
import com.google.caja.reporting.MessageQueue;
import static com.google.caja.plugin.SyntheticNodes.s;

/**
 * Rewrite all references to "this" in an unattached method to
 * {@link ReservedNames#LOCAL_THIS} so that the rewriter will use the public
 * accessors (e.g., {@code ___.readPub}) instead of the method accessors
 * (e.g., {@code ___.readProp}).
 *
 * @author mikesamuel@gmail.com
 */
final class UnattachedMethodRewriter implements Visitor {
  private final MessageQueue mq;

  UnattachedMethodRewriter(MessageQueue mq) {
    this.mq = mq;
  }

  public boolean visit(AncestorChain<?> ac) {
    // Do not descend into closures since their "this" is different.
    if (ac.node instanceof FunctionConstructor) { return false; }

    // End early if we're not looking at a "this" reference.
    if (!(ac.node instanceof Reference)) { return true; }
    Reference ref = ac.cast(Reference.class).node;
    if (!ReservedNames.THIS.equals(ref.getIdentifierName())) { return true; }

    // If used in a context where this would be ambiguous, warn.
    if (mightAccessPrivateApi(ac)) {
      mq.addMessage(
          RewriterMessageType.UNATTACHED_METHOD_AMBIGUITY,
          ac.node.getFilePosition());
    }
    // Make a synthetic reference, so the reference will survive
    // cajoling but will not trigger the readProp/readPub
    // difference.
    Identifier syntheticLocalThis = s(new Identifier(ReservedNames.LOCAL_THIS));
    syntheticLocalThis.setFilePosition(ref.getFilePosition());
    s(ref).replaceChild(syntheticLocalThis, ref.getIdentifier());
    return true;
  }

  private static boolean mightAccessPrivateApi(AncestorChain<?> ac) {
    if (ac.parent == null) { return false; }
    // for (var k in this)...
    if (ac.parent.node instanceof ForEachLoop) { return true; }
    Operator pOp = getOperator(ac.parent);
    if (pOp == null) { return false; }
    switch (pOp) {
      case SQUARE_BRACKET: return true;   // this[x]
      case MEMBER_ACCESS: break;  // Might still be ambiguous
      case IN: // (k in this) can fail if k ends with '_'
        if (ac.node == ac.parent.node.children().get(1)) {
          Expression key = (Expression) ac.parent.node.children().get(0);
          if (key instanceof NumberLiteral) { return false; }
          if (key instanceof StringLiteral) {
            return ((StringLiteral) key).getUnquotedValue().endsWith("_");
          }
          return true;
        }
        return false;
      default: return false;
    }

    Operator gpOp = getOperator(ac.parent.parent);
    if (gpOp == null) { return false; }
    switch (gpOp) {
      case ASSIGN:  // (this.foo = x) can fail if field doesn't exist.
      case DELETE:  // delete this[x] can fail if this is not a JSON object
        return true;
      default:
        return gpOp.getAssignmentDelegate() != null;
    }
  }

  private static Operator getOperator(AncestorChain<?> ac) {
    if (ac == null || !(ac.node instanceof Operation)) { return null; }
    return ac.cast(Operation.class).node.getOperator();
  }
}
