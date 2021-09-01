/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java;

import com.sun.source.doctree.*;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.tree.DCTree;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import org.openrewrite.Tree;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;

public class Java11JavadocVisitor extends DocTreeScanner<Tree, String> {
    private final Attr attr;

    @Nullable
    private final Symbol.TypeSymbol symbol;

    @Nullable
    private final Type enclosingClassType;

    private final TypeMapping typeMapping;
    private final TreeScanner<J, Space> javaVisitor = new JavaVisitor();
    private final Map<Integer, Javadoc.LineBreak> lineBreaks = new HashMap<>();

    /**
     * The whitespace on the first line terminated by a newline (if any)
     */
    private String firstPrefix = "";

    private String source;
    private int cursor = 0;

    public Java11JavadocVisitor(Context context, TreePath scope, TypeMapping typeMapping, String source) {
        this.attr = Attr.instance(context);
        this.typeMapping = typeMapping;
        this.source = source;

        if (scope.getLeaf() instanceof JCTree.JCCompilationUnit) {
            JCTree.JCCompilationUnit cu = (JCTree.JCCompilationUnit) scope.getLeaf();
            this.enclosingClassType = cu.defs.get(0).type;
            this.symbol = cu.packge;
        } else {
            com.sun.source.tree.Tree classDecl = scope.getLeaf();
            if (classDecl instanceof JCTree.JCClassDecl) {
                this.enclosingClassType = ((JCTree.JCClassDecl) classDecl).type;
                this.symbol = ((JCTree.JCClassDecl) classDecl).sym;
            } else if (classDecl instanceof JCTree.JCNewClass) {
                this.enclosingClassType = ((JCTree.JCNewClass) classDecl).def.type;
                this.symbol = ((JCTree.JCNewClass) classDecl).def.sym;
            } else {
                this.enclosingClassType = null;
                this.symbol = null;
            }
        }
    }

    private void init() {
        char[] sourceArr = source.toCharArray();

        StringBuilder firstPrefixBuilder = new StringBuilder();
        StringBuilder javadocContent = new StringBuilder();
        StringBuilder marginBuilder = null;
        boolean inFirstPrefix = true;

        // skip past the opening '/**'
        int i = 3;
        for (; i < sourceArr.length; i++) {
            char c = sourceArr[i];
            if (inFirstPrefix) {
                if (Character.isWhitespace(c)) {
                    firstPrefixBuilder.append(c);
                } else {
                    firstPrefix = firstPrefixBuilder.toString();
                    inFirstPrefix = false;
                }
            }

            if (c == '\n') {
                if (inFirstPrefix) {
                    firstPrefix = firstPrefixBuilder.toString();
                    inFirstPrefix = false;
                } else {
                    if (i > 0 && sourceArr[i - 1] == '\n') {
                        lineBreaks.put(javadocContent.length(), new Javadoc.LineBreak(randomId(), "", Markers.EMPTY));
                    }
                    javadocContent.append(c);
                }
                marginBuilder = new StringBuilder();
            } else if (marginBuilder != null) {
                if (!Character.isWhitespace(c)) {
                    if (c == '*') {
                        marginBuilder.append(c);
                        lineBreaks.put(javadocContent.length(), new Javadoc.LineBreak(randomId(),
                                marginBuilder.toString(), Markers.EMPTY));
                    } else if (c == '@') {
                        lineBreaks.put(javadocContent.length(), new Javadoc.LineBreak(randomId(),
                                marginBuilder.toString(), Markers.EMPTY));
                        javadocContent.append(c);
                    } else {
                        lineBreaks.put(javadocContent.length(), new Javadoc.LineBreak(randomId(),
                                "", Markers.EMPTY));
                        javadocContent.append(marginBuilder).append(c);
                    }
                    marginBuilder = null;
                } else {
                    marginBuilder.append(c);
                }
            } else if (!inFirstPrefix) {
                javadocContent.append(c);
            }
        }

        if (inFirstPrefix) {
            javadocContent.append(firstPrefixBuilder);
        }

        source = javadocContent.toString();

        if (marginBuilder != null && marginBuilder.length() > 0) {
            if (javadocContent.charAt(0) != '\n') {
                lineBreaks.put(javadocContent.length(), new Javadoc.LineBreak(randomId(),
                        marginBuilder.toString(), Markers.EMPTY));
                source = source.substring(0, source.length() - 1); // strip trailing newline
            } else {
                lineBreaks.put(source.length(), new Javadoc.LineBreak(randomId(),
                        marginBuilder.toString(), Markers.EMPTY));
            }
        }
    }

    @Override
    public Tree visitAttribute(AttributeTree node, String fmt) {
        String name = node.getName().toString();
        cursor += name.length();
        List<Javadoc> beforeEqual;
        List<Javadoc> value;

        if (node.getValueKind() == AttributeTree.ValueKind.EMPTY) {
            beforeEqual = emptyList();
            value = emptyList();
        } else {
            beforeEqual = new ArrayList<>();
            value = new ArrayList<>();
            Javadoc.LineBreak lineBreak;
            while ((lineBreak = lineBreaks.remove(cursor)) != null) {
                cursor++;
                beforeEqual.add(lineBreak);
            }
            sourceBefore("=");

            while ((lineBreak = lineBreaks.remove(cursor + 1)) != null) {
                cursor++;
                value.add(lineBreak);
            }

            switch (node.getValueKind()) {
                case UNQUOTED:
                    value.addAll(convertMultiline(node.getValue()));
                    break;
                case SINGLE:
                    value.add(new Javadoc.Text(randomId(), Markers.EMPTY, sourceBefore("'") + "'"));
                    value.addAll(convertMultiline(node.getValue()));
                    value.add(new Javadoc.Text(randomId(), Markers.EMPTY, sourceBefore("'") + "'"));
                    break;
                case DOUBLE:
                default:
                    value.add(new Javadoc.Text(randomId(), Markers.EMPTY, sourceBefore("\"") + "\""));
                    value.addAll(convertMultiline(node.getValue()));
                    value.add(new Javadoc.Text(randomId(), Markers.EMPTY, sourceBefore("\"") + "\""));
                    break;
            }
        }

        return new Javadoc.Attribute(
                randomId(),
                fmt,
                Markers.EMPTY,
                name,
                beforeEqual,
                value
        );
    }

    @Override
    public Tree visitAuthor(AuthorTree node, String fmt) {
        String prefix = fmt + sourceBefore("@author");
        return new Javadoc.Author(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getName()));
    }

    @Override
    public Tree visitComment(CommentTree node, String fmt) {
        String text = fmt + node.getBody();
        cursor += node.getBody().length();
        return new Javadoc.Text(randomId(), Markers.EMPTY, text);
    }

    @Override
    public Tree visitDeprecated(DeprecatedTree node, String fmt) {
        String prefix = fmt + sourceBefore("@deprecated");
        return new Javadoc.Deprecated(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getBody()));
    }

    @Override
    public Tree visitDocComment(DocCommentTree node, String fmt) {
        init();
        List<Javadoc> body = new ArrayList<>();

        Javadoc.LineBreak leadingLineBreak = lineBreaks.remove(0);
        if (leadingLineBreak != null) {
            if (!firstPrefix.isEmpty()) {
                body.add(new Javadoc.Text(randomId(), Markers.EMPTY, firstPrefix.substring(0, firstPrefix.length() - 1)));
                firstPrefix = "";
            }
            body.add(leadingLineBreak);
        }

        List<? extends DocTree> fullBody = node.getFullBody();
        for (int i = 0; i < fullBody.size(); i++) {
            DocTree docTree = fullBody.get(i);
            String prefix = docTree instanceof DCTree.DCText && i > 0 ? "" : whitespaceBefore();
            if (docTree instanceof DCTree.DCText) {
                body.addAll(visitText(((DCTree.DCText) docTree).getBody(), firstPrefix + prefix));
            } else {
                body.add((Javadoc) scan(docTree, firstPrefix + prefix));
            }
            firstPrefix = "";
        }

        Javadoc.LineBreak lineBreak;

        for (DocTree blockTag : node.getBlockTags()) {
            spaceBeforeTags:
            while (true) {
                if ((lineBreak = lineBreaks.remove(cursor + 1)) != null) {
                    cursor++;
                    body.add(lineBreak);
                }

                StringBuilder whitespaceBeforeNewLine = new StringBuilder();
                for (int j = cursor; j < source.length(); j++) {
                    char ch = source.charAt(j);
                    if (ch == '\n') {
                        if (whitespaceBeforeNewLine.length() > 0) {
                            body.add(new Javadoc.Text(randomId(), Markers.EMPTY, whitespaceBeforeNewLine.toString()));
                        }
                        cursor += whitespaceBeforeNewLine.length();
                        break;
                    } else if (Character.isWhitespace(ch)) {
                        whitespaceBeforeNewLine.append(ch);
                    } else {
                        if (whitespaceBeforeNewLine.length() > 0) {
                            body.add(new Javadoc.Text(randomId(), Markers.EMPTY, whitespaceBeforeNewLine.toString()));
                            cursor += whitespaceBeforeNewLine.length();
                        }
                        break spaceBeforeTags;
                    }
                }

                if (lineBreak == null) {
                    break;
                }
            }

            String prefix = whitespaceBefore();
            body.add((Javadoc) scan(blockTag, firstPrefix + prefix));
            firstPrefix = "";
        }

        if (lineBreaks.isEmpty()) {
            if (cursor < source.length()) {
                String trailingWhitespace = source.substring(cursor);
                if (!trailingWhitespace.isEmpty()) {
                    body.add(new Javadoc.Text(randomId(), Markers.EMPTY, trailingWhitespace));
                }
            }
        } else {
            body.addAll(lineBreaks.values());
        }

        return new Javadoc.DocComment(randomId(), Markers.EMPTY, body, "");
    }

    @Override
    public Tree visitDocRoot(DocRootTree node, String fmt) {
        String prefix = fmt + sourceBefore("{@docRoot");
        return new Javadoc.DocRoot(
                randomId(),
                prefix,
                Markers.EMPTY,
                sourceBefore("}")
        );
    }

    @Override
    public Tree visitDocType(DocTypeTree node, String fmt) {
        String prefix = fmt + sourceBefore("<!doctype");
        return new Javadoc.DocType(randomId(), prefix, Markers.EMPTY,
                sourceBefore(node.getText()) + node.getText() + sourceBefore(">"));
    }

    @Override
    public Tree visitEndElement(EndElementTree node, String fmt) {
        String prefix = fmt + sourceBefore("</");
        String name = node.getName().toString();
        cursor += name.length();
        return new Javadoc.EndElement(
                randomId(),
                prefix,
                Markers.EMPTY,
                name,
                sourceBefore(">")
        );
    }

    @Override
    public Tree visitEntity(EntityTree node, String fmt) {
        String text = fmt + sourceBefore("&") + "&" + node.getName().toString() + ";";
        cursor += node.getName().length() + 1;
        return new Javadoc.Text(randomId(), Markers.EMPTY, text);
    }

    @Override
    public Tree visitErroneous(ErroneousTree node, String fmt) {
        return new Javadoc.Erroneous(randomId(), fmt, Markers.EMPTY, visitText(node.getBody(), ""));
    }

    @Override
    public Tree visitHidden(HiddenTree node, String fmt) {
        String prefix = fmt + sourceBefore("@hidden");
        return new Javadoc.Hidden(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getBody()));
    }

    @Override
    public J.Identifier visitIdentifier(com.sun.source.doctree.IdentifierTree node, String fmt) {
        String name = node.getName().toString();
        sourceBefore(name);
        return J.Identifier.build(
                randomId(),
                Space.build(fmt, emptyList()),
                Markers.EMPTY,
                name,
                null
        );
    }

    @Override
    public Tree visitIndex(IndexTree node, String fmt) {
        String prefix = fmt + sourceBefore("{@index");
        Javadoc searchTerm = convert(node.getSearchTerm());

        List<Javadoc> description = convertMultiline(node.getDescription());

        List<Javadoc> paddedDescription = ListUtils.flatMap(description, (i, desc) -> {
            if (i == description.size() - 1) {
                if (desc instanceof Javadoc.Text) {
                    Javadoc.Text text = (Javadoc.Text) desc;
                    return text.withText(text.getText() + sourceBefore("}"));
                } else {
                    return Arrays.asList(desc, new Javadoc.Text(randomId(),
                            Markers.EMPTY, sourceBefore("}")));
                }
            }
            return desc;
        });

        return new Javadoc.Index(
                randomId(),
                prefix,
                Markers.EMPTY,
                searchTerm,
                paddedDescription
        );
    }

    @Override
    public Tree visitInheritDoc(InheritDocTree node, String fmt) {
        String prefix = fmt + sourceBefore("{@inheritDoc");
        return new Javadoc.InheritDoc(
                randomId(),
                prefix,
                Markers.EMPTY,
                sourceBefore("}")
        );
    }

    @Override
    public Tree visitLink(LinkTree node, String fmt) {
        String prefix = fmt + sourceBefore(node.getKind() == DocTree.Kind.LINK ? "{@link" : "{@linkplain");
        J ref = visitReference(node.getReference(), "");
        return new Javadoc.Link(
                randomId(),
                prefix,
                Markers.EMPTY,
                node.getKind() != DocTree.Kind.LINK,
                ref,
                sourceBefore("}")
        );
    }

    @Override
    public Tree visitLiteral(LiteralTree node, String fmt) {
        String prefix = fmt + sourceBefore(node.getKind() == DocTree.Kind.CODE ? "{@code" : "{@literal");

        List<Javadoc> description = visitText(node.getBody().getBody(), whitespaceBefore());
        String suffix = sourceBefore("}");
        if (!suffix.isEmpty()) {
            description.add(new Javadoc.Text(randomId(), Markers.EMPTY, suffix));
        }

        return new Javadoc.Literal(
                randomId(),
                prefix,
                Markers.EMPTY,
                node.getKind() == DocTree.Kind.CODE,
                description
        );
    }

    @Override
    public Tree visitParam(ParamTree node, String fmt) {
        String prefix = fmt + sourceBefore("@param");
        DCTree.DCParam param = (DCTree.DCParam) node;
        J typeName;
        if (param.isTypeParameter) {
            typeName = new J.TypeParameter(
                    randomId(),
                    Space.build(sourceBefore("<"), emptyList()),
                    Markers.EMPTY,
                    emptyList(),
                    visitIdentifier(node.getName(), whitespaceBefore()),
                    null
            );
            sourceBefore(">");
        } else {
            typeName = convert(node.getName());
        }

        return new Javadoc.Parameter(
                randomId(),
                prefix,
                Markers.EMPTY,
                typeName,
                convertMultiline(param.getDescription())
        );
    }

    @Override
    public Tree visitProvides(ProvidesTree node, String fmt) {
        String prefix = fmt + sourceBefore("@provides");
        return new Javadoc.Provides(randomId(), prefix, Markers.EMPTY,
                visitReference(node.getServiceType(), ""),
                convertMultiline(node.getDescription())
        );
    }

    @Override
    public J visitReference(ReferenceTree node, String fmt) {
        DCTree.DCReference ref = (DCTree.DCReference) node;

        TypedTree tree;
        if (ref.qualifierExpression != null) {
            attr.attribType(ref.qualifierExpression, symbol);
            tree = (TypedTree) javaVisitor.scan(ref.qualifierExpression, Space.build(whitespaceBefore(), emptyList()));
        } else {
            tree = J.Identifier.build(randomId(), Space.build(whitespaceBefore(), emptyList()),
                    Markers.EMPTY, "", typeMapping.type(enclosingClassType));
        }

        if (ref.memberName != null) {
            sourceBefore("#");
            J.Identifier name = J.Identifier.build(
                    randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    ref.memberName.toString(),
                    null
            );

            cursor += ref.memberName.toString().length();

            JavaType refType = referenceType(ref, tree.getType());

            if (ref.paramTypes != null) {
                JContainer<Expression> paramContainer;
                Space beforeParen = Space.build(sourceBefore("("), emptyList());
                if (ref.paramTypes.isEmpty()) {
                    paramContainer = JContainer.build(
                            beforeParen,
                            singletonList(JRightPadded.build(new J.Empty(randomId(), Space.build(sourceBefore(")"), emptyList()), Markers.EMPTY))),
                            Markers.EMPTY
                    );
                } else {
                    List<JRightPadded<Expression>> parameters = new ArrayList<>(ref.paramTypes.size());
                    List<JCTree> paramTypes = ref.paramTypes;
                    for (int i = 0; i < paramTypes.size(); i++) {
                        JCTree param = paramTypes.get(i);
                        Expression paramExpr = (Expression) javaVisitor.scan(param, Space.build(whitespaceBefore(), emptyList()));
                        Space rightFmt = Space.format(i == paramTypes.size() - 1 ?
                                sourceBefore(")") : sourceBefore(","));
                        parameters.add(new JRightPadded<>(paramExpr, rightFmt, Markers.EMPTY));
                    }
                    paramContainer = JContainer.build(
                            beforeParen,
                            parameters,
                            Markers.EMPTY
                    );
                }

                return new J.MethodInvocation(
                        randomId(),
                        tree.getPrefix(),
                        Markers.EMPTY,
                        JRightPadded.build(tree.withPrefix(Space.EMPTY)),
                        null,
                        name,
                        paramContainer,
                        TypeUtils.asMethod(refType)
                );
            } else {
                return new J.MemberReference(
                        randomId(),
                        tree.getPrefix(),
                        Markers.EMPTY,
                        JRightPadded.build(tree.withPrefix(Space.EMPTY)),
                        JContainer.empty(),
                        JLeftPadded.build(name),
                        null,
                        refType
                );
            }
        }

        assert tree != null;
        return tree;
    }

    @Nullable
    private JavaType referenceType(DCTree.DCReference ref, @Nullable JavaType type) {
        JavaType.Class classType = TypeUtils.asClass(type);
        if (classType == null) {
            return null;
        }

        nextMethod:
        for (JavaType.Method method : classType.getMethods()) {
            if (method.getName().equals(ref.memberName.toString()) && method.getResolvedSignature() != null) {
                if (ref.paramTypes != null) {
                    for (JCTree param : ref.paramTypes) {
                        for (JavaType testParamType : method.getResolvedSignature().getParamTypes()) {
                            Type paramType = attr.attribType(param, symbol);
                            while (testParamType instanceof JavaType.GenericTypeVariable) {
                                testParamType = ((JavaType.GenericTypeVariable) testParamType).getBound();
                            }

                            if (paramType instanceof Type.ClassType) {
                                JavaType.FullyQualified fqTestParamType = TypeUtils.asFullyQualified(testParamType);
                                if (fqTestParamType == null || !fqTestParamType.getFullyQualifiedName().equals(((Symbol.ClassSymbol) paramType.tsym)
                                        .fullname.toString())) {
                                    continue nextMethod;
                                }
                            }
                        }
                    }
                }

                return method;
            }
        }

        for (JavaType.Variable member : classType.getMembers()) {
            if (member.getName().equals(ref.memberName.toString())) {
                return member.getType();
            }
        }

        // a member reference, but not matching anything on type attribution
        return null;
    }

    @Override
    public Tree visitReturn(ReturnTree node, String fmt) {
        String prefix = fmt + sourceBefore("@return");
        return new Javadoc.Return(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getDescription()));
    }

    @Override
    public Tree visitSee(SeeTree node, String fmt) {
        String prefix = fmt + sourceBefore("@see");
        J ref = null;
        List<Javadoc> docs;
        if (node.getReference().get(0) instanceof DCTree.DCReference) {
            ref = visitReference((ReferenceTree) node.getReference().get(0), "");
            docs = convertMultiline(node.getReference().subList(1, node.getReference().size()));
        } else {
            docs = convertMultiline(node.getReference());
        }

        return new Javadoc.See(randomId(), prefix, Markers.EMPTY, ref, docs);
    }

    @Override
    public Tree visitSerial(SerialTree node, String fmt) {
        String prefix = fmt + sourceBefore("@serial");
        return new Javadoc.Serial(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getDescription()));
    }

    @Override
    public Tree visitSerialData(SerialDataTree node, String fmt) {
        String prefix = fmt + sourceBefore("@serialData");
        return new Javadoc.SerialData(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getDescription()));
    }

    @Override
    public Tree visitSerialField(SerialFieldTree node, String fmt) {
        String prefix = fmt + sourceBefore("@serialField");
        return new Javadoc.SerialField(randomId(), prefix, Markers.EMPTY,
                visitIdentifier(node.getName(), whitespaceBefore()),
                visitReference(node.getType(), whitespaceBefore()),
                convertMultiline(node.getDescription())
        );
    }

    @Override
    public Tree visitSince(SinceTree node, String fmt) {
        String prefix = fmt + sourceBefore("@since");
        return new Javadoc.Since(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getBody()));
    }

    @Override
    public Tree visitStartElement(StartElementTree node, String fmt) {
        String prefix = fmt + sourceBefore("<");
        String name = node.getName().toString();
        cursor += name.length();
        return new Javadoc.StartElement(
                randomId(),
                prefix,
                Markers.EMPTY,
                name,
                convertMultiline(node.getAttributes()),
                node.isSelfClosing(),
                sourceBefore(">")
        );
    }

    @Override
    public Tree visitSummary(SummaryTree node, String fmt) {
        String prefix = fmt + sourceBefore("{@summary");

        List<Javadoc> summary = convertMultiline(node.getSummary());

        List<Javadoc> paddedSummary = ListUtils.flatMap(summary, (i, sum) -> {
            if (i == summary.size() - 1) {
                if (sum instanceof Javadoc.Text) {
                    Javadoc.Text text = (Javadoc.Text) sum;
                    return text.withText(text.getText() + sourceBefore("}"));
                } else {
                    return Arrays.asList(sum, new Javadoc.Text(randomId(),
                            Markers.EMPTY, sourceBefore("}")));
                }
            }
            return sum;
        });

        return new Javadoc.Summary(
                randomId(),
                prefix,
                Markers.EMPTY,
                paddedSummary
        );
    }

    @Override
    public Tree visitVersion(VersionTree node, String fmt) {
        String prefix = fmt + sourceBefore("@version");
        return new Javadoc.Version(randomId(), prefix, Markers.EMPTY, convertMultiline(node.getBody()));
    }

    @Override
    public Tree visitText(TextTree node, String fmt) {
        throw new UnsupportedOperationException("Anywhere text can occur, we need to call the visitText override that " +
                "returns a list of Javadoc elements.");
    }

    public List<Javadoc> visitText(String node, String fmt) {
        List<Javadoc> texts = new ArrayList<>();

        char[] textArr = node.toCharArray();
        StringBuilder text = new StringBuilder(fmt);
        for (char c : textArr) {
            cursor++;
            if (c == '\n') {
                if (text.length() > 0) {
                    texts.add(new Javadoc.Text(randomId(), Markers.EMPTY, text.toString()));
                    text = new StringBuilder();
                }

                Javadoc.LineBreak lineBreak = lineBreaks.remove(cursor);
                assert lineBreak != null;
                texts.add(lineBreak);
            } else {
                text.append(c);
            }
        }

        if (text.length() > 0) {
            texts.add(new Javadoc.Text(randomId(), Markers.EMPTY, text.toString()));
        }

        return texts;
    }

    @Override
    public Tree visitThrows(ThrowsTree node, String fmt) {
        boolean throwsKeyword = source.startsWith("@throws", cursor);
        sourceBefore(throwsKeyword ? "@throws" : "@exception");
        return new Javadoc.Throws(randomId(), fmt, Markers.EMPTY, throwsKeyword,
                visitReference(node.getExceptionName(), ""),
                convertMultiline(node.getDescription())
        );
    }

    @Override
    public Tree visitUnknownBlockTag(UnknownBlockTagTree node, String fmt) {
        String prefix = fmt + sourceBefore("@" + node.getTagName());
        return new Javadoc.UnknownBlock(
                randomId(),
                prefix,
                Markers.EMPTY,
                node.getTagName(),
                convertMultiline(node.getContent())
        );
    }

    @Override
    public Tree visitUnknownInlineTag(UnknownInlineTagTree node, String fmt) {
        String prefix = fmt + sourceBefore("{@" + node.getTagName());
        return new Javadoc.UnknownInline(
                randomId(),
                prefix,
                Markers.EMPTY,
                node.getTagName(),
                sourceBefore("}")
        );
    }

    @Override
    public Tree visitUses(UsesTree node, String fmt) {
        String prefix = fmt + sourceBefore("@uses");
        return new Javadoc.Uses(randomId(), prefix, Markers.EMPTY,
                visitReference(node.getServiceType(), ""),
                convertMultiline(node.getDescription())
        );
    }

    @Override
    public Tree visitValue(ValueTree node, String fmt) {
        String prefix = fmt + sourceBefore("{@value");
        J ref = node.getReference() == null ? null : visitReference(node.getReference(), "");
        return new Javadoc.InlinedValue(
                randomId(),
                prefix,
                Markers.EMPTY,
                ref,
                sourceBefore("}")
        );
    }

    private String sourceBefore(String delim) {
        int endIndex = source.indexOf(delim, cursor);
        if (endIndex < 0) {
            throw new IllegalStateException("Expected to be able to find " + delim);
        }
        String prefix = source.substring(cursor, endIndex);
        cursor = endIndex + delim.length();
        return prefix;
    }

    private String whitespaceBefore() {
        int i = cursor;
        for (; i < source.length(); i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                break;
            }
        }
        String fmt = source.substring(cursor, i);
        cursor = i;
        return fmt;
    }

    private <J2 extends Tree> J2 convert(DocTree t) {
        String prefix = whitespaceBefore();
        @SuppressWarnings("unchecked") J2 j = (J2) scan(t, prefix);
        return j;
    }

    private List<Javadoc> convertMultiline(List<? extends DocTree> dts) {
        List<Javadoc> js = new ArrayList<>(dts.size());
        Javadoc.LineBreak lineBreak;
        for (int i = 0; i < dts.size(); i++) {
            DocTree dt = dts.get(i);
            if (i > 0 && dt instanceof DCTree.DCText) {
                // the whitespace is part of the text
                js.addAll(visitText(((DCTree.DCText) dt).getBody(), ""));
            } else {
                while ((lineBreak = lineBreaks.remove(cursor + 1)) != null) {
                    cursor++;
                    js.add(lineBreak);
                }

                if (dt instanceof DCTree.DCText) {
                    js.addAll(visitText(((DCTree.DCText) dt).getBody(), whitespaceBefore()));
                } else {
                    js.add(convert(dt));
                }
            }
        }
        return js;
    }

    class JavaVisitor extends TreeScanner<J, Space> {

        @Override
        public J visitMemberSelect(MemberSelectTree node, Space fmt) {
            JCTree.JCFieldAccess fieldAccess = (JCTree.JCFieldAccess) node;
            Expression selected = (Expression) scan(fieldAccess.selected, Space.EMPTY);
            sourceBefore(".");
            return new J.FieldAccess(randomId(), fmt, Markers.EMPTY,
                    selected,
                    JLeftPadded.build(J.Identifier.build(randomId(),
                            Space.build(sourceBefore(fieldAccess.name.toString()), emptyList()),
                            Markers.EMPTY,
                            fieldAccess.name.toString(), null)),
                    typeMapping.type(node));
        }

        @Override
        public J visitIdentifier(IdentifierTree node, Space fmt) {
            String name = node.getName().toString();
            cursor += name.length();
            JavaType type = typeMapping.type(node);
            return J.Identifier.build(randomId(), fmt, Markers.EMPTY, name, type);
        }

        @Override
        public J visitPrimitiveType(PrimitiveTypeTree node, Space fmt) {
            JCTree.JCPrimitiveTypeTree primitiveType = (JCTree.JCPrimitiveTypeTree) node;
            String name = primitiveType.toString();
            cursor += name.length();
            return J.Identifier.build(randomId(), fmt, Markers.EMPTY, name, typeMapping.primitiveType(primitiveType.typetag));
        }
    }
}
