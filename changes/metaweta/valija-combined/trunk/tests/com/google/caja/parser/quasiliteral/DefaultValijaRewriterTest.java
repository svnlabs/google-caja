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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.caja.lexer.ParseException;
import com.google.caja.parser.js.Statement;
import com.google.caja.util.RhinoTestBed;

/**
 * @author metaweta@gmail.com
 */
public class DefaultValijaRewriterTest extends RewriterTestCase {

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  public void testIt() throws Exception {
    assertConsistent("1;");
    assertConsistent("var a=0; a;");
    assertConsistent("function f(){ this.x = 1; } var g = new f(); g.x;");
    assertConsistent("function f(){ var y=2; this.x = function(){return y;}; }" +
        "var g = new f(); var h={}; f.call(h); h.y = g.x; h.x() + h.y();");
    assertConsistent("[{},{}].toString();");
    assertConsistent("for (var i=0; i<10; i++) {} i;");
    assertConsistent("var x_=1; x_;");
    
    assertConsistent("(new Date()).toString();");
    assertConsistent("a=5; a;");
    
    assertConsistent("var a={x:1}; delete a.x; a.x;");

    assertConsistent("for (var i in {x:1, y:true}) {} i;");
    checkFails("var o={p_:1}; o.p_;", "Key may not end in \"_\"");
  }

  @Override
  protected Object executePlain(String caja) throws IOException {
    mq.getMessages().clear();
    // Make sure the tree assigns the result to the unittestResult___ var.
    return RhinoTestBed.runJs(
        null,
        new RhinoTestBed.Input(getClass(), "/com/google/caja/caja.js"),
        new RhinoTestBed.Input(getClass(), "../../plugin/asserts.js"),
        new RhinoTestBed.Input(caja, getName() + "-uncajoled"));
  }

  @Override
  protected Object rewriteAndExecute(String pre, String caja, String post)
      throws IOException, ParseException {
    mq.getMessages().clear();

    Statement cajaTree = replaceLastStatementWithEmit(
        js(fromString(caja, is)), "unittestResult___;");
    String cajoledJs = render(
        rewriteStatements(
            //js(fromResource("../../plugin/asserts.js")),
            cajaTree));

    assertNoErrors();

    Object result = RhinoTestBed.runJs(
        null,
        new RhinoTestBed.Input(
            getClass(), "/com/google/caja/plugin/console-stubs.js"),
        new RhinoTestBed.Input(getClass(), "/com/google/caja/caja.js"),
        new RhinoTestBed.Input(getClass(), "/com/google/caja/valija-cajita.js"),
        new RhinoTestBed.Input(getClass(), "../../plugin/asserts.js"),
        new RhinoTestBed.Input(getClass(), "/com/google/caja/log-to-console.js"),
        new RhinoTestBed.Input(
            // Initialize the output field to something containing a unique
            // object value that will not compare identically across runs.
            // Set up the imports environment.
            "var testImports = ___.copy(___.sharedImports);\n" +
            "testImports.unittestResult___ = {\n" +
            "    toString: function () { return '' + this.value; },\n" +
            "    value: '--NO-RESULT--'\n" +
            "};\n" +
            "___.getNewModuleHandler().setImports(testImports);",
            getName() + "-test-fixture"),
        new RhinoTestBed.Input(pre, getName()),
        // Load the cajoled code.
        new RhinoTestBed.Input(
            "___.loadModule(function (___, IMPORTS___) {" + cajoledJs + "\n});",
            getName() + "-cajoled"),
        new RhinoTestBed.Input(post, getName()),
        // Return the output field as the value of the run.
        new RhinoTestBed.Input("unittestResult___;", getName()));

    assertNoErrors();
    return result;
  }

  @Override
  protected List<Rewriter> newRewriters() {
    ArrayList<Rewriter> rewriters = new ArrayList<Rewriter>();
    rewriters.add(new DefaultValijaRewriter(false));
    rewriters.add(new DefaultCajaRewriter(false, false));
    return rewriters;
  }
}
