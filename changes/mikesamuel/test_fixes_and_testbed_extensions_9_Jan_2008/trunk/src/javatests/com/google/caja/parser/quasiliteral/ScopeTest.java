// Copyright (C) 2007 Google Inc.
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
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.CatchStmt;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.TryStmt;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.MessageType;
import com.google.caja.util.TestUtil;
import junit.framework.TestCase;

/**
 *
 * @author ihab.awad@gmail.com
 */
public class ScopeTest extends TestCase {
  private MessageQueue mq;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    mq = TestUtil.createTestMessageQueue(new MessageContext());
  }
  
  public void testSimpleDeclaredFunction() throws Exception {
    ParseTreeNode n = TestUtil.parse(
        "var x = 3;" +
        "function foo() {" +
        "  var y = 3;" +
        "  z = 4;" +
        "};");
    Scope s0 = new Scope(n, mq);
    Scope s1 = new Scope(s0, findFunctionConstructor(n, "foo"));

    assertTrue(s0.isDefined("x"));
    assertTrue(s0.isGlobal("x"));
    assertFalse(s0.isFunction("x"));
    assertFalse(s0.isDeclaredFunction("x"));

    assertTrue(s0.isDefined("foo"));
    assertTrue(s0.isGlobal("foo"));
    assertTrue(s0.isFunction("foo"));
    assertTrue(s0.isDeclaredFunction("foo"));

    assertFalse(s0.isDefined("y"));
    assertFalse(s0.isFunction("y"));
    assertFalse(s0.isDeclaredFunction("y"));

    assertFalse(s0.isDefined("z"));
    assertFalse(s0.isFunction("z"));
    assertFalse(s0.isDeclaredFunction("z"));

    assertTrue(s1.isDefined("x"));
    assertTrue(s1.isGlobal("x"));
    assertFalse(s1.isFunction("x"));
    assertFalse(s1.isDeclaredFunction("x"));

    assertTrue(s1.isDefined("foo"));
    assertTrue(s1.isGlobal("foo"));
    assertTrue(s1.isFunction("foo"));
    assertTrue(s1.isDeclaredFunction("foo"));

    assertTrue(s1.isDefined("y"));
    assertFalse(s1.isGlobal("y"));
    assertFalse(s1.isFunction("y"));
    assertFalse(s1.isDeclaredFunction("y"));

    assertFalse(s1.isDefined("z"));
    assertFalse(s1.isFunction("z"));
    assertFalse(s1.isDeclaredFunction("z"));    
  }

  public void testAnonymousFunction() throws Exception {
    ParseTreeNode n = TestUtil.parse(
        "var x = function() {};");
    Scope s0 = new Scope(n, mq);
    Scope s1 = new Scope(s0, findFunctionConstructor(n, null));

    assertTrue(s0.isDefined("x"));
    assertTrue(s0.isGlobal("x"));
    assertFalse(s0.isFunction("x"));
    assertFalse(s0.isDeclaredFunction("x"));

    assertTrue(s1.isDefined("x"));
    assertTrue(s1.isGlobal("x"));
    assertFalse(s1.isFunction("x"));
    assertFalse(s1.isDeclaredFunction("x"));
  }

  public void testNamedFunction() throws Exception {
    ParseTreeNode n = TestUtil.parse(
        "var x = function foo() {};");
    Scope s0 = new Scope(n, mq);
    Scope s1 = new Scope(s0, findFunctionConstructor(n, "foo"));
    
    assertTrue(s0.isDefined("x"));
    assertTrue(s0.isGlobal("x"));
    assertFalse(s0.isFunction("x"));
    assertFalse(s0.isDeclaredFunction("x"));

    assertFalse(s0.isDefined("foo"));
    assertFalse(s0.isFunction("foo"));
    assertFalse(s0.isDeclaredFunction("foo"));

    assertTrue(s1.isDefined("x"));
    assertTrue(s1.isGlobal("x"));
    assertFalse(s1.isFunction("x"));
    assertFalse(s1.isDeclaredFunction("x"));

    assertTrue(s1.isDefined("foo"));
    assertFalse(s1.isGlobal("foo"));
    assertTrue(s1.isFunction("foo"));
    assertFalse(s1.isDeclaredFunction("foo"));
  }

  public void testNamedFunctionSameName() throws Exception {
    ParseTreeNode n = TestUtil.parse(
        "var x = function x() {};");
    Scope s0 = new Scope(n, mq);
    Scope s1 = new Scope(s0, findFunctionConstructor(n, "x"));

    assertTrue(s0.isDefined("x"));
    assertTrue(s0.isGlobal("x"));
    assertFalse(s0.isFunction("x"));
    assertFalse(s0.isDeclaredFunction("x"));

    assertTrue(s1.isDefined("x"));
    assertFalse(s1.isGlobal("x"));
    assertTrue(s1.isFunction("x"));
    assertFalse(s1.isDeclaredFunction("x"));    
  }

  public void testFormalParams() throws Exception {
    ParseTreeNode n = TestUtil.parse(
        "function f(x) {};");
    Scope s0 = new Scope(n, mq);
    Scope s1 = new Scope(s0, findFunctionConstructor(n, "f"));

    assertFalse(s0.isDefined("x"));
    assertTrue(s1.isDefined("x"));    
  }

  public void testCatchBlocks() throws Exception {
    ParseTreeNode n = TestUtil.parse(
        "try { } catch (e) { var x; }");

    Block b = (Block) n;
    TryStmt t = (TryStmt) b.children().get(0);
    CatchStmt c = (CatchStmt) t.children().get(1);
    
    Scope s0 = new Scope(n, mq);
    Scope s1 = new Scope(s0, c);

    // e only defined in catch scope
    assertFalse(s0.isDefined("e"));
    assertTrue(s1.isDefined("e"));
    assertTrue(s1.isException("e"));

    // Definition of x appears in main scope
    assertTrue(s0.isDefined("x"));
  }

  public void testMaskedVariables() throws Exception {
    ParseTreeNode n = TestUtil.parse(
        "var e; try { } catch (e) { var x; }");

    Block b = (Block) n;
    TryStmt t = (TryStmt) b.children().get(1);
    CatchStmt c = (CatchStmt) t.children().get(1);
    
    Scope s0 = new Scope(n, mq);
    Scope s1 = new Scope(s0, c);

    assertEquals(
        MessageType.MASKING_SYMBOL,
        mq.getMessages().get(0).getMessageType());
    assertTrue(
        MessageLevel.ERROR.compareTo(
            mq.getMessages().get(0).getMessageLevel())
        <= 0);
  }

  private FunctionConstructor findFunctionConstructor(ParseTreeNode root, String name) {
    return findNodeWithIdentifier(root, FunctionConstructor.class, name);
  }

  private static class Holder<T> { T value; }

  @SuppressWarnings("unchecked")
  private <T extends ParseTreeNode> T findNodeWithIdentifier(
      ParseTreeNode root,
      final Class<T> clazz,
      final String identifierValue) {
    final Holder<T> result = new Holder<T>();

    root.acceptPreOrder(new Visitor() {
      public boolean visit(AncestorChain<?> chain) {
        if (clazz.isAssignableFrom(chain.node.getClass()) &&
            chain.node.children().size() > 0 &&
            chain.node.children().get(0) instanceof Identifier) {
          Identifier id = (Identifier)chain.node.children().get(0);
          if ((identifierValue == null && id.getValue() == null) ||
              identifierValue.equals(id.getValue())) {
            assertNull(result.value);
            result.value = (T)chain.node;
            return false;
          }
        }
        return true;
      }
    },
    null);

    assertNotNull(result.value);
    return result.value;
  }
}
