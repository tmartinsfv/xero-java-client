// Copyright 2014 Connectifier, Inc. All Rights Reserved.

package com.connectifier.xeroclient.jaxb;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import com.sun.codemodel.JClass;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JFormatter;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JOp;
import com.sun.codemodel.JType;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;

public class PluginImpl extends Plugin {

  @Override
  public String getOptionName() {
    return "Xcustom";
  }

  @Override
  public String getUsage() {
    return "  -Xcustom alter generated code with client library improvements";
  }

  @Override
  public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) throws SAXException {
    JCodeModel model = outline.getModel().codeModel;
    for (ClassOutline co : outline.getClasses()) {
      updateArrayOfGetters(co, model);
      updateArrayOfSetters(co, model);
    }

    return true;
  }

  /**
   * Update getters to use Java List. For example:
   * ArrayOfInvoices getInvoices() -> List<Invoice> getInvoices()
   */
  private void updateArrayOfGetters(ClassOutline co, JCodeModel model) {
    JDefinedClass implClass = co.implClass;

    List<JMethod> removedMethods = new ArrayList<>();
    Iterator<JMethod> iter = implClass.methods().iterator();
    while (iter.hasNext()) {
      JMethod method = iter.next();
      if (method.type().name().startsWith("ArrayOf")) {
        removedMethods.add(method);
        iter.remove();
      }
    }

    for (JMethod removed : removedMethods) {
      // Parse the old code to get the variable name
      StringWriter oldWriter = new StringWriter();
      removed.body().state(new JFormatter(oldWriter));
      String oldBody = oldWriter.toString();
      String varName = oldBody.substring(oldBody.indexOf("return ") + "return ".length(), oldBody.indexOf(";"));

      // Build the new method
      JClass newReturnType = (JClass) ((JDefinedClass) removed.type()).fields().values().iterator().next().type();
      JMethod newMethod = implClass.method(removed.mods().getValue(), newReturnType, removed.name());
      JFieldVar field = implClass.fields().get(varName);          
      JClass typeParameter = newReturnType.getTypeParameters().get(0);
      String fieldName = model._getClass(field.type().fullName()).fields().keySet().iterator().next();

      newMethod.body()._return(
          JOp.cond(field.eq(JExpr._null()),
              JExpr._new(model.ref("java.util.ArrayList").narrow(typeParameter)),
              field.invoke("get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1))));
    }    
  }

  /**
   * Update setters to use Java List. For example:
   * setLineItems(ArrayOfLineItem value) -> setLineItems(List<LineItem> value)
   */
  private void updateArrayOfSetters(ClassOutline co, JCodeModel model) {
    JDefinedClass implClass = co.implClass;

    List<JMethod> removedMethods = new ArrayList<>();
    Iterator<JMethod> iter = implClass.methods().iterator();
    while (iter.hasNext()) {
      JMethod method = iter.next();
      if (method.params().size() == 1 && method.params().get(0).type().name().startsWith("ArrayOf")) {
        removedMethods.add(method);
        iter.remove();
      }
    }

    for (JMethod removed : removedMethods) {
      // Parse the old code to get the variable name
      StringWriter oldWriter = new StringWriter();
      removed.body().state(new JFormatter(oldWriter));
      String oldBody = oldWriter.toString();
      String varName = oldBody.substring(oldBody.indexOf("this.") + "this.".length(), oldBody.indexOf(" = "));

      // Build the new method
      JType arrType = removed.params().get(0).type();
      String type = arrType.name().substring("ArrayOf".length());
      JFieldVar field = implClass.fields().get(varName);  
      String fieldName = model._getClass(field.type().fullName()).fields().keySet().iterator().next();

      JMethod newMethod = implClass.method(removed.mods().getValue(), Void.TYPE, removed.name());
      newMethod.param(model.ref("java.util.List").narrow(model.ref(type)), "value");
      newMethod.body().decl(arrType, "arr", JExpr._new(arrType));
      newMethod.body().directStatement("arr.get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1) + "().addAll(value);");
      newMethod.body().directStatement("this." + varName + " = arr;");
    }
  }

}
