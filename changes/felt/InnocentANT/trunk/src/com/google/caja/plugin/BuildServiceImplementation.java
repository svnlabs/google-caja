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

package com.google.caja.plugin;

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.ExternalReference;
import com.google.caja.lexer.InputSource;
import com.google.caja.lexer.ParseException;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Minify;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.MessageType;
import com.google.caja.reporting.RenderContext;
import com.google.caja.reporting.SimpleMessageQueue;
import com.google.caja.reporting.SnippetProducer;
import com.google.caja.tools.BuildService;
import com.google.caja.util.Pair;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build integration to {@link PluginCompiler} and {@link Minify}.
 *
 * @author mikesamuel@gmail.com
 */
public class BuildServiceImplementation implements BuildService {
  private final Map<InputSource, String> originalSources
      = new HashMap<InputSource, String>();

  /**
   * Cajoles inputs to output writing any messages to logger, returning true
   * iff the task passes.
   */
  public boolean cajole(
      PrintWriter logger, List<File> dependees, List<File> inputs, File output,
      Map<String, Object> options) {
    final Set<File> canonFiles = new HashSet<File>();
    try {
      for (File f : dependees) { canonFiles.add(f.getCanonicalFile()); }
      for (File f : inputs) { canonFiles.add(f.getCanonicalFile()); }
    } catch (IOException ex) {
      logger.println(ex.toString());
      return false;
    }
    final MessageQueue mq = new SimpleMessageQueue();

    PluginEnvironment env = new PluginEnvironment() {
        public CharProducer loadExternalResource(
            ExternalReference ref, String mimeType) {
          URI uri = ref.getUri();
          uri = ref.getReferencePosition().source().getUri().resolve(uri);
          InputSource is = new InputSource(uri);

          try {
            if (!canonFiles.contains(new File(uri).getCanonicalFile())) {
              return null;
            }
          } catch (IllegalArgumentException ex) {
            return null;  // Not a file reference.
          } catch (IOException ex) {
            return null;  // Not a file reference.
          }

          try {
            String content = getSourceContent(is);
            if (content == null) { return null; }
            return CharProducer.Factory.create(new StringReader(content), is);
          } catch (IOException ex) {
            mq.addMessage(MessageType.IO_ERROR, is);
            return null;
          }
        }

        public String rewriteUri(ExternalReference uri, String mimeType) {
          try {
            return URI.create(
                "http://proxy/"
                + "?mime-type=" + URLEncoder.encode(mimeType, "UTF-8")
                + "&uri=" + URLEncoder.encode("" + uri.getUri(), "UTF-8"))
                .toString();
          } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex);
          }
        }
      };

    // Set up the cajoler
    PluginMeta meta = new PluginMeta(env);
    meta.setDebugMode(Boolean.TRUE.equals(options.get("debug")));
    meta.setWartsMode(Boolean.TRUE.equals(options.get("warts")));    
    PluginCompiler compiler = new PluginCompiler(meta, mq);

    boolean passed = true;

    // Parse inputs
    for (File f : inputs) {
      try {
        AncestorChain<?> parsedInput
            = parseInput(new InputSource(f.getCanonicalFile().toURI()), mq);
        if (parsedInput == null) {
          passed = false;
        } else {
          compiler.addInput(parsedInput);
        }
      } catch (IOException ex) {
        logger.println("Failed to read " + f);
        passed = false;
      }
    }

    // Cajole
    passed = passed && compiler.run();

    // Log messages
    MessageContext mc = compiler.getMessageContext();
    SnippetProducer snippetProducer = new SnippetProducer(originalSources, mc);
    for (Message msg : mq.getMessages()) {
      if (passed && MessageLevel.LOG.compareTo(msg.getMessageLevel()) >= 0) {
        continue;
      }
      String snippet = snippetProducer.getSnippet(msg);
      if (!"".equals(snippet)) { snippet = "\n" + snippet; }
      logger.println(msg.getMessageLevel() + " : " + msg.format(mc) + snippet);
    }

    // Write the output
    if (passed) {
      Block block = compiler.getJavascript();
      StringBuilder out = new StringBuilder();
      RenderContext rc = new RenderContext(mc, block.makeRenderer(out, null));
      block.render(rc);
      rc.getOut().noMoreTokens();
      try {
        Writer w = new OutputStreamWriter(new FileOutputStream(output));
        try {
          w.write(out.toString());
        } finally {
          w.close();
        }
      } catch (IOException ex) {
        logger.println("Failed to write " + output);
        return false;
      }
    }
    return passed;
  }

  private String getSourceContent(InputSource is) throws IOException {
    String content = originalSources.get(is);
    if (content == null) {
      File f = new File(is.getUri());
      // Read it in and stuff it back in the map so we can generate
      // snippets.
      Reader in = new InputStreamReader(new FileInputStream(f), "UTF-8");
      try {
        char[] buf = new char[4096];
        StringBuilder sb = new StringBuilder();
        for (int n; (n = in.read(buf, 0, buf.length)) > 0;) {
          sb.append(buf, 0, n);
        }
        content = sb.toString();
      } finally {
        in.close();
      }
      originalSources.put(is, content);
    }
    return content;
  }

  private AncestorChain<?> parseInput(InputSource is, MessageQueue mq)
      throws IOException {
    CharProducer cp = CharProducer.Factory.create(
        new StringReader(getSourceContent(is)), is);
    try {
      return new AncestorChain<ParseTreeNode>(
          PluginCompilerMain.parseInput(is, cp, mq));
    } catch (ParseException ex) {
      ex.toMessageQueue(mq);
      return null;
    }
  }

  /**
   * Minifies inputs to output writing any messages to logger, returning true
   * iff the task passes.
   */
  public boolean minify(
      PrintWriter logger, List<File> dependees, List<File> inputs, File output,
      Map<String, Object> options) {
    try {
      List<Pair<InputSource, File>> inputSources
          = new ArrayList<Pair<InputSource, File>>();
      for (File f : inputs) {
        inputSources.add(
            Pair.pair(new InputSource(f.getAbsoluteFile().toURI()), f));
      }
      Writer outputWriter = new OutputStreamWriter(
          new FileOutputStream(output), "UTF-8");
      try {
        return Minify.minify(inputSources, outputWriter, logger);
      } finally {
        outputWriter.close();
      }
    } catch (IOException ex) {
      logger.println("Minifying failed: " + ex);
      return false;
    }
  }
  
  /**
   * Applies the innocent code transformer to inputs.  Writes
   * any messages to logger and returns true iff the task passes.
   */
  public boolean innocent(
      PrintWriter logger, List<File> dependees, List<File> inputs, File output,
      Map<String, Object> options) {
    try {
      List<Pair<InputSource, File>> inputSources
          = new ArrayList<Pair<InputSource, File>>();
      for (File f : inputs) {
        inputSources.add(
            Pair.pair(new InputSource(f.getAbsoluteFile().toURI()), f));
      }
      Writer outputWriter = new OutputStreamWriter(
          new FileOutputStream(output), "UTF-8");
      try {
        
        return Minify.minify(inputSources, outputWriter, logger);
      } finally {
        outputWriter.close();
      }
    } catch (IOException ex) {
      logger.println("Innocent transform failed: " + ex);
      return false;
    }
  }
}