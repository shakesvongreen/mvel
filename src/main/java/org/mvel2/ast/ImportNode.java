/**
 * MVEL 2.0
 * Copyright (C) 2007 The Codehaus
 * Mike Brock, Dhanji Prasanna, John Graham, Mark Proctor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mvel2.ast;

import org.mvel2.CompileException;
import org.mvel2.MVEL;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.integration.impl.ImmutableDefaultFactory;
import org.mvel2.util.ParseTools;

import static org.mvel2.util.ParseTools.findClassImportResolverFactory;

/**
 * @author Christopher Brock
 */
public class ImportNode extends ASTNode {
  private Class importClass;
  private boolean packageImport;
  private int _offset;

  private static final char[] WC_TEST = new char[]{'.', '*'};

  public ImportNode(char[] expr, int start, int offset) {
    this.expr = expr;
    this.start = start;
    this.offset = offset;

    if (ParseTools.endsWith(expr, start, offset, WC_TEST)) {
      packageImport = true;
      _offset = (short) ParseTools.findLast(expr, start, offset, '.');
      if (_offset == -1) {
        _offset = 0;
      }
    }
    else {
      String clsName = new String(expr, start, offset);

      try {
        this.importClass = Class.forName(clsName, true,
            Thread.currentThread().getContextClassLoader());
      }
      catch (ClassNotFoundException e) {
        int idx;
        clsName = (clsName.substring(0, idx = clsName.lastIndexOf('.')) + "$" + clsName.substring(idx + 1)).trim();

        try {
          this.importClass = Class.forName(clsName, true, Thread.currentThread().getContextClassLoader());
        }
        catch (ClassNotFoundException e2) {
          throw new CompileException("class not found: " + new String(expr), expr, start);
        }
      }
    }
  }


  public Object getReducedValueAccelerated(Object ctx, Object thisValue, VariableResolverFactory factory) {
    if (!packageImport) {
      if (MVEL.COMPILER_OPT_ALLOCATE_TYPE_LITERALS_TO_SHARED_SYMBOL_TABLE) {
        factory.createVariable(importClass.getSimpleName(), importClass);
        return importClass;
      }
      return findClassImportResolverFactory(factory).addClass(importClass);
    }

    // if the factory is an ImmutableDefaultFactory it means this import is unused so we can skip it safely
    if (!(factory instanceof ImmutableDefaultFactory)) {
      findClassImportResolverFactory(factory).addPackageImport(new String(expr, start, _offset - start));
    }
    return null;
  }

  public Object getReducedValue(Object ctx, Object thisValue, VariableResolverFactory factory) {
    return getReducedValueAccelerated(ctx, thisValue, factory);
  }


  public Class getImportClass() {
    return importClass;
  }

  public boolean isPackageImport() {
    return packageImport;
  }

  public void setPackageImport(boolean packageImport) {
    this.packageImport = packageImport;
  }

  public String getPackageImport() {
    return new String(expr, start, _offset - start);
  }
}

