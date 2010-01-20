// Copyright (C) 2009 Google Inc.
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

package com.google.caja.plugin.stages;

import com.google.caja.lang.html.HTML;
import com.google.caja.lang.html.HtmlSchema;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.escaping.UriUtil;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.html.AttribKey;
import com.google.caja.parser.html.ElKey;
import com.google.caja.parser.html.Nodes;
import com.google.caja.plugin.Dom;
import com.google.caja.plugin.Job;
import com.google.caja.plugin.Jobs;
import com.google.caja.util.ContentType;
import com.google.caja.util.Pipeline;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ListIterator;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * For each HTML job, tries to determine a base URI.  If it can, it will resolve
 * well-formed relative URIs in attributes.
 *
 * @author mikesamuel@gmail.com
 */
public class ResolveUriStage implements Pipeline.Stage<Jobs> {
  private static final ElKey BASE = ElKey.forHtmlElement("base");
  private static final AttribKey BASE_HREF
      = AttribKey.forHtmlAttrib(BASE, "href");

  private final HtmlSchema schema;

  public ResolveUriStage(HtmlSchema schema) {
    this.schema = schema;
  }

  private static boolean isBaseUri(URI uri) {
    return uri != null && uri.isAbsolute() && !uri.isOpaque();
  }

  private URI baseUri(Node root, URI uri, FilePosition pos) {
    URI baseUri = baseUriForDoc(root);
    if (!isBaseUri(baseUri)) {
      baseUri = uri;
      if (!isBaseUri(baseUri)) {
        // TODO(mikesamuel): this is problematic for DOM nodes parsed without
        // proper debugging info.
        baseUri = pos.source().getUri();
        if (!isBaseUri(baseUri)) { return null; }
      }
    }
    return baseUri;
  }

  private URI baseUriForDoc(Node root) {
    if (root instanceof Element) {
      Element el = (Element) root;
      if (BASE.is((Element) root)) {
        return uriFromBaseElement(el);
      } else {
        for (Element base : Nodes.nodeListIterable(
            el.getElementsByTagName(BASE.qName),
            Element.class)) {
          URI uri = uriFromBaseElement(base);
          if (uri != null) { return uri; }
        }
      }
      return null;
    } else {
      for (Node c : Nodes.childrenOf(root)) {
        URI uri = baseUriForDoc(c);
        if (uri != null) { return uri; }
      }
    }
    return null;
  }

  private URI uriFromBaseElement(Element base) {
    Attr a = base.getAttributeNode(BASE_HREF.qName);
    if (a == null) { return null; }
    String value = a.getValue();
    try {
      URI uri = new URI(value);
      return isBaseUri(uri) ? uri : null;
    } catch (URISyntaxException ex) {
      return null;
    }
  }

  public boolean apply(Jobs jobs) {
    ListIterator<Job> it = jobs.getJobs().listIterator();
    while (it.hasNext()) {
      Job job = it.next();
      if (job.getType() != ContentType.HTML) { continue; }
      AncestorChain<Dom> root = job.getRoot().cast(Dom.class);
      Dom dom = job.getRoot().cast(Dom.class).node;
      Node node = dom.getValue();
      URI baseUri = baseUri(node, job.getBaseUri(), dom.getFilePosition());
      if (baseUri != null) {
        resolveRelativeUrls(node, baseUri);
        it.set(Job.domJob(root, baseUri));
      }
    }
    return true;
  }

  private void resolveRelativeUrls(Node n, URI base) {
    if (n instanceof Element) {
      Element el = (Element) n;
      ElKey elKey = ElKey.forElement(el);
      for (Attr a : Nodes.attributesOf(el)) {
        AttribKey aKey = AttribKey.forAttribute(elKey, a);
        // If we ignored a relative base href, don't make it valid based on a
        // later one.
        if (BASE_HREF.equals(aKey)) { continue; }
        HTML.Attribute attrInfo = schema.lookupAttribute(aKey);
        if (attrInfo != null && attrInfo.getType() == HTML.Attribute.Type.URI) {
          String value = a.getValue();
          // Don't muck with inter-document references.
          if (value.startsWith("#")) { continue; }
          URI uri = UriUtil.resolve(base, value);
          if (uri != null && uri.isAbsolute()) {
            FilePosition valuePos = Nodes.getFilePositionForValue(a);
            a.setValue(base.resolve(uri).toString());
            Nodes.setFilePositionForValue(a, valuePos);
          }
        }
      }
    }
    for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
      resolveRelativeUrls(c, base);
    }
  }
}
