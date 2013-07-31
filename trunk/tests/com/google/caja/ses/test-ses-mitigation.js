// Copyright (C) 2013 Google Inc.
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

// First test that the ses.rewriter_.* functions on which mitigateGotchas
// relies are correct. These are all from third-party code, so we need merely
// do a brief sanity check. This should screen out setup issues that would
// otherwise cause strange test failures downstream.

jsunitRegister('testParse', function testParse() {
  var ast = ses.rewriter_.parse('1 + 2;');
  assertEquals('Program', ast.type);
  assertEquals('ExpressionStatement', ast.body[0].type);
  assertEquals('+', ast.body[0].expression.operator);
  jsunitPass('testParse');
});

jsunitRegister('testGenerate', function testGenerate() {
  var str = ses.rewriter_.generate(ses.rewriter_.parse('1 + 2;'));
  assertNotEquals(-1, str.indexOf('1 + 2'));
  jsunitPass('testGenerate');
});

jsunitRegister('testTraverse', function testTraverse() {
  var seen = '';
  ses.rewriter_.traverse(ses.rewriter_.parse('1 + 2;'), {
      enter: function enter(node) {
        if (node.type === 'BinaryExpression') {
          seen += node.left.value + node.operator + node.right.value;
        }
      },
      leave: function leave(node) { }
    });
  assertEquals('1+2', seen);
  jsunitPass('testTraverse');
});

// Now actually test mitigateGotchas.

function assertMitigate(expected, input, options) {
  function scrub(s) { return ses.rewriter_.generate(ses.rewriter_.parse(s)); }
  var actual = ses.mitigateSrcGotchas(input, options, console);
  // We re-parse the mitigated code because the mitigator adds comments
  assertEquals(scrub(expected), scrub(actual));
}

function assertNoMitigate(src, options) {
  assertMitigate(src, src, options);
}

jsunitRegister('testRewritePropertyUpdateExpr',
    function testRewritePropertyUpdateExpr() {
      var o = { rewritePropertyUpdateExpr: true };

      assertNoMitigate('x++;', o);

      assertMitigate('o[(1, "x")]++;', 'o.x++;', o);
      assertMitigate('o[(1, "x")]--;', 'o.x--;', o);
      assertMitigate('o[(1, "x")]++;', 'o["x"]++;', o);
      assertMitigate('o[(1, "x")]--;', 'o["x"]--;', o);
      assertMitigate('o[(1, 3)]++;', 'o[3]++;', o);
      assertMitigate('o[(1, 3)]--;', 'o[3]--;', o);

      assertNoMitigate('o[3 + 4]++;', o);
      assertNoMitigate('o[p]++;', o);

      assertMitigate('o[q[(1, "y")]++]--;', 'o[q.y++]--;', o);
      assertMitigate('foo(q[(1, "y")]++)[(1, 3)]--;', 'foo(q.y++)[3]--;', o);

      jsunitPass('testRewritePropertyUpdateExpr');
    });

jsunitRegister('testRewritePropertyCompoundAssignmentExpr',
    function testRewritePropertyCompoundAssignmentExpr() {
      var o = { rewritePropertyCompoundAssignmentExpr: true };

      assertNoMitigate('x += 1;', o);
      assertNoMitigate('o.x = 1;', o);
      assertNoMitigate('o.x = o.x + 1;', o);

      assertMitigate('o[(1, "x")] += 1;', 'o.x += 1;', o);
      assertMitigate('o[(1, "x")] += 1;', 'o["x"] += 1;', o);
      assertMitigate('o[(1, 3)] += 1;', 'o[3] += 1;', o);
      assertMitigate('o[(1, "x")] -= 1;', 'o.x -= 1;', o);
      assertMitigate('o[(1, "x")] *= 1;', 'o.x *= 1;', o);
      assertMitigate('o[(1, "x")] /= 1;', 'o.x /= 1;', o);
      assertMitigate('o[(1, "x")] &= 1;', 'o.x &= 1;', o);
      assertMitigate('o[(1, "x")] |= 1;', 'o.x |= 1;', o);
      assertMitigate('o[(1, "x")] ^= 1;', 'o.x ^= 1;', o);
      assertMitigate('o[(1, "x")] %= 1;', 'o.x %= 1;', o);
      assertMitigate('o[(1, "x")] <<= 1;', 'o.x <<= 1;', o);
      assertMitigate('o[(1, "x")] >>= 1;', 'o.x >>= 1;', o);
      assertMitigate('o[(1, "x")] >>>= 1;', 'o.x >>>= 1;', o);

      assertMitigate(
          'foo(o[(1, "x")] += 1)[(1, "z")] += (q[(1, "y")] += 1)',
          'foo(o.x += 1).z += (q.y += 1);',
          o);

      jsunitPass('testRewritePropertyCompoundAssignmentExpr');
    });

jsunitRun();