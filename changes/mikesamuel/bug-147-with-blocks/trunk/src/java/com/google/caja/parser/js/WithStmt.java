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

package com.google.caja.parser.js;

import com.google.caja.parser.ParseTreeNode;
import com.google.caja.reporting.RenderContext;

import java.io.IOException;
import java.util.List;

/**
 * ES262-12.10: The with statement adds a computed object to the front
 * of the scope chain of the current execution context, then executes a
 * statement with this augmented scope chain, then restores the scope
 * chain.
 *
 * @author mikesamuel@gmail.com
 */
public final class WithStmt extends AbstractStatement<ParseTreeNode>
    implements NestedScope {

  public WithStmt(Void value, List<? extends Statement> children) {
    createMutation().appendChildren(children).execute();
  }

  public WithStmt(Expression scopeObject, Statement body) {
    createMutation()
        .appendChild(scopeObject)
        .appendChild(body)
        .execute();
  }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();
    if (children().size() != 2) { throw new IllegalStateException(); }
    getScopeObject();
    getBody();
  }

  public Expression getScopeObject() { return (Expression) children().get(0); }
  public Statement getBody() { return (Statement) children().get(1); }

  @Override
  public Object getValue() { return null; }

  public void render(RenderContext rc) throws IOException {
    rc.out.append("with (");
    getScopeObject().render(rc);
    rc.out.append(")");
    getBody().renderBlock(rc, true, false, false);
  }
}
