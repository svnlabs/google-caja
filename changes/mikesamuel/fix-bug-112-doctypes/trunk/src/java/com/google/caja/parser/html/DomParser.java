// Copyright (C) 2006 Google Inc.
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

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.HtmlLexer;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.lexer.InputSource;
import com.google.caja.lexer.ParseException;
import com.google.caja.lexer.Token;
import com.google.caja.lexer.TokenQueue;
import com.google.caja.lexer.TokenStream;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.MessageType;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a {@link DomTree} from a stream of XML or HTML tokens.
 * This is a non-validating parser, that, will parse tolerantly when created
 * in HTML mode, or will require balanced tags when created in XML mode.
 * <p>
 * Since it's not validating, we don't bother to parse DTDs, and so does not
 * process external entities.  Parsing will not cause URI resolution or
 * fetching.
 *
 * @author mikesamuel@gmail.com
 */
public final class DomParser {
  private final TokenQueue<HtmlTokenType> tokens;
  private final boolean asXml;
  private final MessageQueue mq;

  public DomParser(TokenQueue<HtmlTokenType> tokens, boolean asXml,
                   MessageQueue mq) {
    this.tokens = tokens;
    this.asXml = asXml;
    this.mq = mq;
  }

  /**
   * Guesses the markup type -- HTML vs XML -- by looking at the first token.
   */
  public DomParser(HtmlLexer lexer, InputSource src, MessageQueue mq)
      throws ParseException {
    this.mq = mq;
    LookaheadLexer la = new LookaheadLexer(lexer);
    lexer.setTreatedAsXml(this.asXml = guessAsXml(la, src));
    this.tokens = new TokenQueue<HtmlTokenType>(la, src);
  }

  public TokenQueue<HtmlTokenType> getTokenQueue() { return tokens; }

  protected OpenElementStack makeElementStack(MessageQueue mq) {
    return asXml
        ? OpenElementStack.Factory.createXmlElementStack()
        : OpenElementStack.Factory.createHtml5ElementStack(mq);
  }

  /** Parse a document returning the document element. */
  public DomTree parseDocument() throws ParseException {
    OpenElementStack elementStack = makeElementStack(mq);

    // Make sure the elementStack is empty.
    elementStack.open(false);

    do {
      Token<HtmlTokenType> t = tokens.peek();
      if (HtmlTokenType.TEXT == t.type) {
        if (!"".equals(t.text.trim())) { break; }
      } else if (HtmlTokenType.COMMENT != t.type
                 && HtmlTokenType.DIRECTIVE != t.type) {
        break;
      }
      tokens.advance();
      continue;
    } while (!tokens.isEmpty());

    do {
      parseDom(elementStack);
    } while (!tokens.isEmpty());

    FilePosition endPos = FilePosition.endOf(tokens.lastPosition());
    try {
      elementStack.finish(endPos);
    } catch (IllegalDocumentStateException ex) {
      throw new ParseException(ex.getCajaMessage(), ex);
    }

    DomTree root = elementStack.getRootElement();
    if (root.children().isEmpty()) {
      throw new ParseException(new Message(
          DomParserMessageType.MISSING_DOCUMENT_ELEMENT, endPos));
    }
    return root.children().get(0);
  }

  /** Parses a snippet of markup. */
  public DomTree.Fragment parseFragment() throws ParseException {
    OpenElementStack elementStack = makeElementStack(mq);

    // Make sure the elementStack is empty.
    elementStack.open(true);

    while (!tokens.isEmpty()) {
      // Skip over top level comments, and whitespace only text nodes.
      // Whitespace is significant for XML unless the schema specifies
      // otherwise, but whitespace outside the root element is not.  There is
      // one exception for whitespace preceding the prologue.
      Token<HtmlTokenType> t = tokens.peek();

      if (HtmlTokenType.COMMENT == t.type) {
        tokens.advance();
        continue;
      }

      parseDom(elementStack);
    }

    FilePosition endPos = FilePosition.endOf(tokens.lastPosition());
    try {
      elementStack.finish(endPos);
    } catch (IllegalDocumentStateException ex) {
      throw new ParseException(ex.getCajaMessage(), ex);
    }

    return elementStack.getRootElement();
  }

  /**
   * Creates a TokenQueue suitable for this class's parse methods.
   * @param asXml true to parse as XML, false as HTML.
   */
  public static TokenQueue<HtmlTokenType> makeTokenQueue(
      InputSource is, Reader in, boolean asXml) {
    return makeTokenQueue(FilePosition.startOfFile(is), in, asXml);
  }
  /**
   * Creates a TokenQueue suitable for this class's parse methods.
   * @param pos the position of the first character on in.
   * @param asXml true to parse as XML, false as HTML.
   */
  public static TokenQueue<HtmlTokenType> makeTokenQueue(
      FilePosition pos, Reader in, boolean asXml) {
    CharProducer cp = CharProducer.Factory.create(in, pos);
    HtmlLexer lexer = new HtmlLexer(cp);
    lexer.setTreatedAsXml(asXml);
    return new TokenQueue<HtmlTokenType>(lexer, pos.source());
  }

  /**
   * Parses a single top level construct, an element, or a text chunk from the
   * given queue.
   * @throws ParseException if elements are unbalanced -- sgml instead of xml
   *   attributes are missing values, or there is no top level construct to
   *   parse, or if there is a problem parsing the underlying stream.
   */
  private void parseDom(OpenElementStack out) throws ParseException {
    while (true) {
      Token<HtmlTokenType> t = tokens.pop();
      switch (t.type) {
        case TAGBEGIN:
          {
            List<DomTree.Attrib> attribs;
            Token<HtmlTokenType> end;
            if (isClose(t)) {
              attribs = Collections.<DomTree.Attrib>emptyList();
              do {
                // TODO(mikesamuel): if this is not a tagend, then we should
                // require ignorable whitespace when we're parsing strictly.
                end = tokens.pop();
              } while (end.type != HtmlTokenType.TAGEND);
            } else {
              attribs = new ArrayList<DomTree.Attrib>();
              end = parseTagAttributes(attribs);

              for (DomTree.Attrib attrib : attribs) {
                attrib.setAttribName(
                    out.canonicalizeAttributeName(attrib.getAttribName()));
              }
            }
            attribs = Collections.unmodifiableList(attribs);
            try {
              out.processTag(t, end, attribs);
            } catch (IllegalDocumentStateException ex) {
              throw new ParseException(ex.getCajaMessage(), ex);
            }
          }
          return;
        case CDATA:
        case TEXT:
        case UNESCAPED:
          out.processText(t);
          return;
        case COMMENT:
          continue;
        default:
          throw new ParseException(new Message(
              MessageType.MALFORMED_XHTML, t.pos,
              MessagePart.Factory.valueOf(t.text)));
      }
    }
  }

  /**
   * Parses attributes onto children and consumes and returns the end of tag
   * token.
   */
  private Token<HtmlTokenType> parseTagAttributes(
      List<? super DomTree.Attrib> children)
      throws ParseException {
    Token<HtmlTokenType> last;
    tokloop:
    while (true) {
      last = tokens.peek();
      switch (last.type) {
      case TAGEND:
        tokens.advance();
        break tokloop;
      case ATTRNAME:
        children.add(parseAttrib());
        break;
      default:
        throw new ParseException(new Message(
            MessageType.MALFORMED_XHTML, last.pos,
            MessagePart.Factory.valueOf(last.text)));
      }
    }
    return last;
  }

  /**
   * Parses an element from a token stream.
   */
  private DomTree.Attrib parseAttrib() throws ParseException {
    Token<HtmlTokenType> name = tokens.pop();
    Token<HtmlTokenType> value = tokens.pop();
    // TODO(mikesamuel): make sure that the XmlElementStack does not allow
    // valueless attributes, and allow them here.
    if (value.type != HtmlTokenType.ATTRVALUE) {
      throw new ParseException(
          new Message(MessageType.MALFORMED_XHTML,
                      value.pos, MessagePart.Factory.valueOf(value.text)));
    }
    return new DomTree.Attrib(new DomTree.Value(value), name, name);
  }

  /**
   * True iff the given tag is an end tag.
   * @param t a token with type {@link HtmlTokenType#TAGBEGIN}
   */
  private static boolean isClose(Token<HtmlTokenType> t) {
    return t.text.startsWith("</");
  }

  private static boolean guessAsXml(LookaheadLexer la, InputSource is)
      throws ParseException {
    Token<HtmlTokenType> first = la.peek();
    if (first != null && "".equals(first.text.trim())) {
      Token<HtmlTokenType> space = first;
      la.next();
      first = la.peek();
      la.pushBack(space);
    }
    if (first == null) {
      return false;  // An empty document is not valid XML.
    }
    switch (first.type) {
      case DIRECTIVE:
        return first.text.startsWith("<?xml")
            || (first.text.startsWith("<!DOCTYPE")
                && !isHtmlDoctype(first.text));
      case TAGBEGIN:
        if (first.text.indexOf(':') >= 0) { return true; }
        break;
    }
    String path = is.getUri().getPath();
    if (path != null) {
      String ext = path.toLowerCase().substring(path.lastIndexOf('.' + 1));
      if ("html".equals(ext)) { return false; }
      if ("xml".equals(ext) || ".xhtml".equals(ext)) { return true; }
    }
    return false;
  }

  private static boolean isHtmlDoctype(String s) {
    // http://htmlhelp.com/tools/validator/doctype.html
    return Pattern.compile("(?i:^<!DOCTYPE\\s+HTML\\b)").matcher(s).find()
        && !Pattern.compile("(?i:\\bXHTML\\b)").matcher(s).find();
  }
}


final class LookaheadLexer implements TokenStream<HtmlTokenType> {
  private final TokenStream<HtmlTokenType> lexer;
  private List<Token<HtmlTokenType>> pending
      = new LinkedList<Token<HtmlTokenType>>();

  LookaheadLexer(TokenStream<HtmlTokenType> lexer) {
    this.lexer = lexer;
  }

  /** True if {@link #next} is safe to call. */
  public boolean hasNext() throws ParseException {
    return !pending.isEmpty() || lexer.hasNext();
  }
  /** Returns the next value, and moves the stream position forward. */
  public Token<HtmlTokenType> next() throws ParseException {
    return pending.isEmpty() ? lexer.next() : pending.remove(0);
  }
  Token<HtmlTokenType> peek() throws ParseException {
    if (pending.isEmpty()) {
      Token<HtmlTokenType> next = lexer.next();
      pending.add(next);
      return next;
    }
    return pending.get(pending.size() - 1);
  }

  void pushBack(Token<HtmlTokenType> t) {
    pending.add(0, t);
  }
}
