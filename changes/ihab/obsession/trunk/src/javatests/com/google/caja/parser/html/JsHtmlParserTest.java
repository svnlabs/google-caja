// Copyright (C) 2005 Google Inc.
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

package com.google.caja.parser.html;

import com.google.caja.util.CajaTestCase;
import com.google.caja.util.TestUtil;
import com.google.caja.reporting.MessageContext;
import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.HtmlLexer;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.lexer.TokenQueue;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.js.Statement;

import java.io.IOException;

/**
 *
 * @author mikesamuel@gmail.com
 */
public class JsHtmlParserTest extends CajaTestCase {

  public void testParser() throws Exception {
    final MessageContext mc = new MessageContext();
    CharProducer cp = fromResource("htmlparsertest1.gxp");
    HtmlLexer lexer = new HtmlLexer(cp);
    lexer.setTreatedAsXml(true);
    Statement parseTree = new JsHtmlParser(
        new TokenQueue<HtmlTokenType>(lexer, cp.getCurrentPosition().source()),
        mq).parse();

    StringBuilder output = new StringBuilder();
    parseTree.format(mc, output);

    parseTree.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode n = ancestors.node;
          if (null == n.getFilePosition()) {
            StringBuilder sb = new StringBuilder();
            try {
              n.format(mc, sb);
            } catch (IOException ex) {
              ex.printStackTrace();
              throw new AssertionError();
            }
            throw new NullPointerException(sb.toString());
          }
          return true;
        }
      }, null);

    // check that parse tree matches
    String golden = TestUtil.readResource(getClass(), "htmlparsergolden1.txt");
    // get rid of classpath artifacts in anonymously named functions' names
    String actual = output.toString().replaceAll(
        "Identifier : _.*_com_google",
        "Identifier : ___com_google");
    assertEquals(golden, actual);
  }

}