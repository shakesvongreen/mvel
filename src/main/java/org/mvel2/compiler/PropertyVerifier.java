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

package org.mvel2.compiler;

import org.mvel2.*;
import org.mvel2.ast.Function;
import org.mvel2.optimizers.AbstractOptimizer;
import org.mvel2.optimizers.impl.refl.nodes.WithAccessor;
import org.mvel2.util.ErrorUtil;
import org.mvel2.util.NullType;
import org.mvel2.util.ParseTools;
import org.mvel2.util.StringAppender;

import java.lang.reflect.*;
import java.util.*;

import static org.mvel2.util.ParseTools.*;
import static org.mvel2.util.PropertyTools.getFieldOrAccessor;

/**
 * This verifier is used by the compiler to enforce rules such as type strictness.  It is, as side-effect, also
 * responsible for extracting type information.
 *
 * @author Mike Brock
 * @author Dhanji Prasanna
 */
public class PropertyVerifier extends AbstractOptimizer {
  private static final int DONE = -1;
  private static final int NORM = 0;
  private static final int METH = 1;
  private static final int COL = 2;
  private static final int WITH = 3;

  private List<String> inputs = new LinkedList<String>();
  private boolean first = false;
  private boolean classLiteral = false;
  private boolean resolvedExternally;
  private boolean methodCall = false;
  private boolean deepProperty = false;
  private boolean fqcn = false;

  private Map<String, Type> paramTypes;

  private Class ctx = null;

  public PropertyVerifier(char[] property, ParserContext parserContext) {
    this.length = end = (this.expr = property).length;
    this.pCtx = parserContext;
  }

  public PropertyVerifier(char[] property, int start, int offset, ParserContext parserContext) {
    this.expr = property;
    this.start = start;
    this.length = offset;
    this.end = start + offset;

    this.pCtx = parserContext;
  }

  public PropertyVerifier(String property, ParserContext parserContext) {
    this.length = end = (this.expr = property.toCharArray()).length;
    this.pCtx = parserContext;
  }

  public PropertyVerifier(String property, ParserContext parserContext, Class root) {
    this.end = this.length = (this.expr = property.toCharArray()).length;

    if (property.length() > 0 && property.charAt(0) == '.') {
      this.cursor = this.st = this.start = 1;
    }

    this.pCtx = parserContext;
    this.ctx = root;
  }

  public List<String> getInputs() {
    return inputs;
  }

  public void setInputs(List<String> inputs) {
    this.inputs = inputs;
  }

  /**
   * Analyze the statement and return the known egress type.
   *
   * @return known engress type
   */
  public Class analyze() {
    cursor = start;
    resolvedExternally = true;
    if (ctx == null) {
      ctx = Object.class;
      first = true;
    }

    while (cursor < end) {
      classLiteral = false;
      switch (nextSubToken()) {
        case NORM:
          ctx = getBeanProperty(ctx, capture());
          break;
        case METH:
          ctx = getMethod(ctx, capture());
          break;
        case COL:
          ctx = getCollectionProperty(ctx, capture());
          break;
        case WITH:
          ctx = getWithProperty(ctx);
          break;

        case DONE:
          break;
      }
      if (cursor < length && !first) deepProperty = true;

      first = false;
    }
    return ctx;
  }

  private void recordTypeParmsForProperty(String property) {
    if (pCtx.isStrictTypeEnforcement()) {
      if ((paramTypes = pCtx.getTypeParameters(property)) == null) {
        pCtx.addTypeParameters(property, pCtx.getVarOrInputType(property));
      }
      pCtx.setLastTypeParameters(pCtx.getTypeParametersAsArray(property));
    }
  }

  /**
   * Process bean property
   *
   * @param ctx      - the ingress type
   * @param property - the property component
   * @return known egress type.
   */
  private Class getBeanProperty(Class ctx, String property) {
    if (first) {
      if (pCtx.hasVarOrInput(property)) {
        if (pCtx.isStrictTypeEnforcement()) {
          recordTypeParmsForProperty(property);
        }
        return pCtx.getVarOrInputType(property);
      }
      else if (pCtx.hasImport(property)) {
        resolvedExternally = false;
        return pCtx.getImport(property);
      }
      else if (!pCtx.isStrongTyping()) {
        return Object.class;

      }
      else if (pCtx.hasVarOrInput("this")) {
        if (pCtx.isStrictTypeEnforcement()) {
          recordTypeParmsForProperty("this");
        }
        ctx = pCtx.getVarOrInputType("this");
        resolvedExternally = false;
      }
    }

    st = cursor;
    boolean switchStateReg;

    Member member = ctx != null ? getFieldOrAccessor(ctx, property) : null;

    if (MVEL.COMPILER_OPT_SUPPORT_JAVA_STYLE_CLASS_LITERALS && ctx != null) {
      if ("class".equals(property)) {
        return (Class<?>) ctx;
      }
    }

    if (member instanceof Field) {
      if (pCtx.isStrictTypeEnforcement()) {
        Field f = ((Field) member);

        if (f.getGenericType() != null && f.getGenericType() instanceof ParameterizedType) {
          ParameterizedType pt = (ParameterizedType) f.getGenericType();
          pCtx.setLastTypeParameters(pt.getActualTypeArguments());

          Type[] gpt = pt.getActualTypeArguments();
          Type[] classArgs = ((Class) pt.getRawType()).getTypeParameters();

          if (gpt.length > 0 && paramTypes == null) paramTypes = new HashMap<String, Type>();
          for (int i = 0; i < gpt.length; i++) {
            paramTypes.put(classArgs[i].toString(), gpt[i]);
          }
        }

        return f.getType();
      }
      else {
        return ((Field) member).getType();
      }
    }

    if (member != null) {
      Method method = (Method) member;

      if (pCtx.isStrictTypeEnforcement()) {
        //if not a field, then this is a property getter
        Type parametricReturnType = method.getGenericReturnType();

        //push return type parameters onto parser context, only if this is a parametric type
        if (parametricReturnType instanceof ParameterizedType) {
          pCtx.setLastTypeParameters(((ParameterizedType) parametricReturnType).getActualTypeArguments());
          ParameterizedType pt = (ParameterizedType) parametricReturnType;

          Type[] gpt = pt.getActualTypeArguments();
          Type[] classArgs = ((Class) pt.getRawType()).getTypeParameters();

          if (gpt.length > 0 && paramTypes == null) paramTypes = new HashMap<String, Type>();
          for (int i = 0; i < gpt.length; i++) {
            paramTypes.put(classArgs[i].toString(), gpt[i]);
          }
        }
      }

      Class rt = method.getReturnType();
      return rt.isPrimitive() ? boxPrimitive(rt) : rt;
    }

    if (pCtx != null && pCtx.hasImport(property)) {
      Class<?> importedClass = pCtx.getImport(property);
      if (importedClass != null) return pCtx.getImport(property);
    }

    if (pCtx != null && pCtx.getLastTypeParameters() != null && pCtx.getLastTypeParameters().length != 0
        && ((Collection.class.isAssignableFrom(ctx) && !(switchStateReg = false))
        || (Map.class.isAssignableFrom(ctx) && (switchStateReg = true)))) {
      Type parm = pCtx.getLastTypeParameters()[switchStateReg ? 1 : 0];
      pCtx.setLastTypeParameters(null);

      if (parm instanceof ParameterizedType) {
        return Object.class;
      }
      else {
        return (Class) parm;
      }
    }

    if (pCtx != null && "length".equals(property) && ctx.isArray()) {
      return Integer.class;
    }

    Object tryStaticMethodRef = tryStaticAccess();

    if (tryStaticMethodRef != null) {
      fqcn = true;
      resolvedExternally = false;
      if (tryStaticMethodRef instanceof Class) {
        classLiteral = !(MVEL.COMPILER_OPT_SUPPORT_JAVA_STYLE_CLASS_LITERALS &&
            new String(expr, cursor - start - 6, 6).equals(".class"));
        return (Class) tryStaticMethodRef;
      }
      else if (tryStaticMethodRef instanceof Field) {
        try {
          return ((Field) tryStaticMethodRef).get(null).getClass();
        }
        catch (Exception e) {
          throw new CompileException("in verifier: ", expr, start, e);
        }
      }
      else {
        try {
          return ((Method) tryStaticMethodRef).getReturnType();
        }
        catch (Exception e) {
          throw new CompileException("in verifier: ", expr, start, e);
        }
      }
    }

    if (ctx != null && ctx.getClass() == Class.class) {
      for (Method m : ctx.getMethods()) {
        if (property.equals(m.getName())) {
          return m.getReturnType();
        }
      }
      try {
        return findClass(variableFactory, ctx.getName() + "$" + property, null);
      }
      catch (ClassNotFoundException cnfe) {
        // fall through.
      }
    }

    if (MVEL.COMPILER_OPT_ALLOW_NAKED_METH_CALL) {
      Class cls = getMethod(ctx, property);
      if (cls != Object.class) {
        return cls;
      }
    }

    if (pCtx.isStrictTypeEnforcement()) {
      throw new CompileException("unqualified type in strict mode for: " + property, expr, tkStart);
    }

    return Object.class;
  }

  /**
   * Process collection property
   *
   * @param ctx      - the ingress type
   * @param property - the property component
   * @return known egress type
   */
  private Class getCollectionProperty(Class ctx, String property) {
    if (first) {
      if (pCtx.hasVarOrInput(property)) {
        ctx = getSubComponentType(pCtx.getVarOrInputType(property));
      }
      else if (pCtx.hasImport(property)) {
        resolvedExternally = false;
        ctx = getSubComponentType(pCtx.getImport(property));
      }
      else {
        ctx = Object.class;
      }
    }

    if (pCtx.isStrictTypeEnforcement()) {
      if (Map.class.isAssignableFrom(property.length() != 0 ? ctx = getBeanProperty(ctx, property) : ctx)) {
        ctx = (Class) (pCtx.getLastTypeParameters().length != 0 ? pCtx.getLastTypeParameters()[1] : Object.class);
      }
      else if (Collection.class.isAssignableFrom(ctx)) {
        if (pCtx.getLastTypeParameters().length == 0 ) {
          ctx = Object.class;
        } else {
          Type type = pCtx.getLastTypeParameters()[0];
          if (type instanceof Class) ctx = (Class)type;
          else ctx = (Class)((ParameterizedType)type).getRawType();
        }
      }
      else if (ctx.isArray()) {
        ctx = getBaseComponentType(ctx);
      }
      else if (pCtx.isStrongTyping()) {
        throw new CompileException("unknown collection type: " + ctx + "; property=" + property, expr, start);
      }
    }
    else {
      ctx = Object.class;
    }

    ++cursor;

    skipWhitespace();

    int start = cursor;

    if (cursor == length)
      throw new PropertyAccessException("unterminated '['", expr, this.start);

    if (scanTo(']')) {
      addFatalError("unterminated [ in token");
    }

    MVEL.analysisCompile(new String(expr, start, cursor - start), pCtx);

    ++cursor;

    return ctx;
  }


  /**
   * Process method
   *
   * @param ctx  - the ingress type
   * @param name - the property component
   * @return known egress type.
   */
  private Class getMethod(Class ctx, String name) {
    int st = cursor;

    /**
     * Check to see if this is the first element in the statement.
     */
    if (first) {
      first = false;
      methodCall = true;

      /**
       * It's the first element in the statement, therefore we check to see if there is a static import of a
       * native Java method or an MVEL function.
       */
      if (pCtx.hasImport(name)) {
        Method m = pCtx.getStaticImport(name).getMethod();

        /**
         * Replace the method parameters.
         */
        ctx = m.getDeclaringClass();
        name = m.getName();
      }
      else if (pCtx.hasFunction(name)) {
        resolvedExternally = false;
        Function f = pCtx.getFunction(name);
        f.checkArgumentCount(
            parseParameterList(
                (((cursor = balancedCapture(expr, cursor, end, '(')) - st) > 1 ?
                    ParseTools.subset(expr, st + 1, cursor - st - 1) : new char[0]), 0, -1).size());

        return f.getEgressType();
      }
      else if (pCtx.hasVarOrInput("this")) {
        if (pCtx.isStrictTypeEnforcement()) {
          recordTypeParmsForProperty("this");
        }
        ctx = pCtx.getVarOrInputType("this");
        resolvedExternally = false;
      }
    }

    /**
     * Get the arguments for the method.
     */
    String tk;

    if (cursor < end && expr[cursor] == '(' && ((cursor = balancedCapture(expr, cursor, end, '(')) - st) > 1) {
      tk = new String(expr, st + 1, cursor - st - 1);
    }
    else {
      tk = "";
    }

    cursor++;

    /**
     * Parse out the arguments list.
     */
    Class[] args;
    List<char[]> subtokens = parseParameterList(tk.toCharArray(), 0, -1);

    if (subtokens.size() == 0) {
      args = new Class[0];
      subtokens = Collections.emptyList();
    }
    else {
      //   ParserContext subCtx = pCtx.createSubcontext();
      args = new Class[subtokens.size()];

      /**
       *  Subcompile all the arguments to determine their known types.
       */
      //  ExpressionCompiler compiler;

      List<ErrorDetail> errors = pCtx.getErrorList().isEmpty() ?
          pCtx.getErrorList() : new ArrayList<ErrorDetail>(pCtx.getErrorList());

      CompileException rethrow = null;
      for (int i = 0; i < subtokens.size(); i++) {
        try {
          args[i] = MVEL.analyze(subtokens.get(i), pCtx);

          if ("null".equals(String.valueOf(subtokens.get(i)))) {
            args[i] = NullType.class;
          }

        }
        catch (CompileException e) {
          rethrow = ErrorUtil.rewriteIfNeeded(e, expr, this.st);
        }

        if (errors.size() < pCtx.getErrorList().size()) {
          for (ErrorDetail detail : pCtx.getErrorList()) {
            if (!errors.contains(detail)) {
              detail.setExpr(expr);
              detail.setCursor(new String(expr).substring(this.st).indexOf(new String(subtokens.get(i))) + this.st);
              detail.setColumn(0);
              detail.setLineNumber(0);
              detail.calcRowAndColumn();
            }
          }
        }

        if (rethrow != null) {
          throw rethrow;
        }
      }
    }

    /**
     * If the target object is an instance of java.lang.Class itself then do not
     * adjust the Class scope target.
     */

    Method m;

    /**
     * If we have not cached the method then we need to go ahead and try to resolve it.
     */

    if ((m = getBestCandidate(args, name, ctx, ctx.getMethods(), pCtx.isStrongTyping())) == null) {
      if ((m = getBestCandidate(args, name, ctx, ctx.getDeclaredMethods(), pCtx.isStrongTyping())) == null) {
        StringAppender errorBuild = new StringAppender();
        for (int i = 0; i < args.length; i++) {
          errorBuild.append(args[i] != null ? args[i].getName() : null);
          if (i < args.length - 1) errorBuild.append(", ");
        }

        if (("size".equals(name) || "length".equals(name)) && args.length == 0 && ctx.isArray()) {
          return Integer.class;
        }

        if (pCtx.isStrictTypeEnforcement()) {
          throw new CompileException("unable to resolve method using strict-mode: "
              + ctx.getName() + "." + name + "(" + errorBuild.toString() + ")", expr, tkStart);
        }

        return Object.class;
      }
    }

    /**
     * If we're in strict mode, we look for generic type information.
     */
    if (pCtx.isStrictTypeEnforcement() && m.getGenericReturnType() != null) {
      Map<String, Class> typeArgs = new HashMap<String, Class>();

      Type[] gpt = m.getGenericParameterTypes();
      Class z;
      ParameterizedType pt;

      for (int i = 0; i < gpt.length; i++) {
        if (gpt[i] instanceof ParameterizedType) {
          pt = (ParameterizedType) gpt[i];
          if ((z = pCtx.getImport(new String(subtokens.get(i)))) != null) {
            /**
             * We record the value of the type parameter to our typeArgs Map.
             */
            if (pt.getRawType().equals(Class.class)) {
              /**
               * If this is an instance of Class, we deal with the special parameterization case.
               */
              typeArgs.put(pt.getActualTypeArguments()[0].toString(), z);
            }
            else {
              typeArgs.put(gpt[i].toString(), z);
            }
          }
        }
      }

      if (pCtx.isStrictTypeEnforcement() && ctx.getTypeParameters().length != 0 && pCtx.getLastTypeParameters() !=
          null && pCtx.getLastTypeParameters().length == ctx.getTypeParameters().length) {

        TypeVariable[] typeVariables = ctx.getTypeParameters();
        for (int i = 0; i < typeVariables.length; i++) {
          Type typeArg = pCtx.getLastTypeParameters()[i];
          typeArgs.put(typeVariables[i].getName(), typeArg instanceof Class ? (Class) pCtx.getLastTypeParameters()[i] : Object.class);
        }
      }

      /**
       * Get the return type argument
       */
      Type parametricReturnType = m.getGenericReturnType();
      String returnTypeArg = parametricReturnType.toString();

      //push return type parameters onto parser context, only if this is a parametric type
      if (parametricReturnType instanceof ParameterizedType) {
        pCtx.setLastTypeParameters(((ParameterizedType) parametricReturnType).getActualTypeArguments());
      }

      if (paramTypes != null && paramTypes.containsKey(returnTypeArg)) {
        /**
         * If the paramTypes Map contains the known type, return that type.
         */
        return (Class) paramTypes.get(returnTypeArg);
      }
      else if (typeArgs.containsKey(returnTypeArg)) {
        /**
         * If the generic type was declared as part of the method, it will be in this
         * Map.
         */
        return typeArgs.get(returnTypeArg);
      }
    }

    if (!Modifier.isPublic(m.getModifiers())) {
      StringAppender errorBuild = new StringAppender();
      for (int i = 0; i < args.length; i++) {
        errorBuild.append(args[i] != null ? args[i].getName() : null);
        if (i < args.length - 1) errorBuild.append(", ");
      }

      String scope = Modifier.toString(m.getModifiers());
      if (scope.trim().equals("")) scope = "<package local>";

      addFatalError("the referenced method is not accessible: "
          + ctx.getName() + "." + name + "(" + errorBuild.toString() + ")"
          + " (scope: " + scope + "; required: public", this.tkStart);
    }

    return m.getReturnType();
  }

  private Class getWithProperty(Class ctx) {
    String root = new String(expr, 0, cursor - 1).trim();

    int start = cursor + 1;
    cursor = balancedCaptureWithLineAccounting(expr, cursor, end, '{', pCtx);

    new WithAccessor(root, expr, start, cursor++ - start, ctx, pCtx.isStrictTypeEnforcement());

    return ctx;
  }

  public boolean isResolvedExternally() {
    return resolvedExternally;
  }

  public boolean isClassLiteral() {
    return classLiteral;
  }

  public boolean isDeepProperty() {
    return deepProperty;
  }

  public boolean isInput() {
    return resolvedExternally && !methodCall;
  }

  public boolean isMethodCall() {
    return methodCall;
  }

  public boolean isFqcn() {
    return fqcn;
  }

  public Class getCtx() {
    return ctx;
  }

  public void setCtx(Class ctx) {
    this.ctx = ctx;
  }
}
