/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.dsl.processor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Registration;
import com.oracle.truffle.dsl.processor.java.ElementUtils;

@SupportedAnnotationTypes("com.oracle.truffle.api.TruffleLanguage.Registration")
public final class LanguageRegistrationProcessor extends AbstractProcessor {
    private final List<TypeElement> registrations = new ArrayList<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    private void generateFile(List<TypeElement> languages) {
        String filename = "META-INF/truffle/language";
        // sorted properties
        Properties p = new SortedProperties();
        int cnt = 0;
        for (TypeElement l : languages) {
            Registration annotation = l.getAnnotation(Registration.class);
            if (annotation == null) {
                continue;
            }
            String prefix = "language" + ++cnt + ".";
            String className = processingEnv.getElementUtils().getBinaryName(l).toString();
            String id = annotation.id();
            if (id != null && !id.isEmpty()) {
                p.setProperty(prefix + "id", id);
            }
            p.setProperty(prefix + "name", annotation.name());
            p.setProperty(prefix + "implementationName", annotation.implementationName());
            p.setProperty(prefix + "version", annotation.version());
            p.setProperty(prefix + "className", className);
            String[] mimes = annotation.mimeType();
            for (int i = 0; i < mimes.length; i++) {
                p.setProperty(prefix + "mimeType." + i, mimes[i]);
            }
            String[] dependencies = annotation.dependentLanguages();
            Arrays.sort(dependencies);
            for (int i = 0; i < dependencies.length; i++) {
                p.setProperty(prefix + "dependentLanguage." + i, dependencies[i]);
            }
            p.setProperty(prefix + "interactive", Boolean.toString(annotation.interactive()));
            p.setProperty(prefix + "internal", Boolean.toString(annotation.internal()));
        }
        if (cnt > 0) {
            try {
                FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", filename, languages.toArray(new Element[0]));
                try (OutputStream os = file.openOutputStream()) {
                    p.store(os, "Generated by " + LanguageRegistrationProcessor.class.getName());
                }
            } catch (IOException e) {
                if (e instanceof FilerException) {
                    if (e.getMessage().startsWith("Source file already created")) {
                        // ignore source file already created errors
                        return;
                    }
                }
                processingEnv.getMessager().printMessage(Kind.ERROR, e.getMessage(), languages.get(0));
            }
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            generateFile(registrations);
            registrations.clear();
            return true;
        }
        for (Element e : roundEnv.getElementsAnnotatedWith(Registration.class)) {
            Registration annotation = e.getAnnotation(Registration.class);
            if (annotation != null && e.getKind() == ElementKind.CLASS) {
                if (!e.getModifiers().contains(Modifier.PUBLIC)) {
                    emitError("Registered language class must be public", e);
                    continue;
                }
                if (e.getEnclosingElement().getKind() != ElementKind.PACKAGE && !e.getModifiers().contains(Modifier.STATIC)) {
                    emitError("Registered language inner-class must be static", e);
                    continue;
                }
                TypeMirror truffleLang = processingEnv.getTypeUtils().erasure(ElementUtils.getTypeElement(processingEnv, TruffleLanguage.class.getName()).asType());
                if (!processingEnv.getTypeUtils().isAssignable(e.asType(), truffleLang)) {
                    emitError("Registered language class must subclass TruffleLanguage", e);
                    continue;
                }
                boolean foundConstructor = false;
                for (ExecutableElement constructor : ElementFilter.constructorsIn(e.getEnclosedElements())) {
                    if (!constructor.getModifiers().contains(Modifier.PUBLIC)) {
                        continue;
                    }
                    if (!constructor.getParameters().isEmpty()) {
                        continue;
                    }
                    foundConstructor = true;
                    break;
                }

                Element singletonElement = null;
                for (Element mem : e.getEnclosedElements()) {
                    if (!mem.getModifiers().contains(Modifier.PUBLIC)) {
                        continue;
                    }
                    if (mem.getKind() != ElementKind.FIELD) {
                        continue;
                    }
                    if (!mem.getModifiers().contains(Modifier.FINAL)) {
                        continue;
                    }
                    if (!"INSTANCE".equals(mem.getSimpleName().toString())) {
                        continue;
                    }
                    if (processingEnv.getTypeUtils().isAssignable(mem.asType(), truffleLang)) {
                        singletonElement = mem;
                        break;
                    }
                }

                if (singletonElement != null) {
                    emitWarning("Using a singleton field is deprecated. Please provide a public no-argument constructor instead.", singletonElement);
                } else {
                    if (!foundConstructor) {
                        emitError("A TruffleLanguage subclass must have a public no argument constructor.", e);
                    } else {
                        assertNoErrorExpected(e);
                    }
                }

                registrations.add((TypeElement) e);
            }
        }

        return true;
    }

    void assertNoErrorExpected(Element e) {
        ExpectError.assertNoErrorExpected(processingEnv, e);
    }

    void emitError(String msg, Element e) {
        if (ExpectError.isExpectedError(processingEnv, e, msg)) {
            return;
        }
        processingEnv.getMessager().printMessage(Kind.ERROR, msg, e);
    }

    void emitWarning(String msg, Element e) {
        if (ExpectError.isExpectedError(processingEnv, e, msg)) {
            return;
        }
        processingEnv.getMessager().printMessage(Kind.WARNING, msg, e);
    }

    @SuppressWarnings("serial")
    static class SortedProperties extends Properties {
        @Override
        public synchronized Enumeration<Object> keys() {
            return Collections.enumeration(new TreeSet<>(super.keySet()));
        }
    }

}
