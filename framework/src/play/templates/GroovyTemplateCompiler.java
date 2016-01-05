package play.templates;

import groovy.lang.Closure;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import play.Play;
import play.Logger;
import play.exceptions.TemplateCompilationException;
import play.templates.GroovyInlineTags.CALL;

/**
 * The template compiler
 */
public class GroovyTemplateCompiler extends TemplateCompiler {

    static public List<String> extensionsClassnames = new ArrayList<String>();

    // [#714] The groovy-compiler complaints if a line is more than 65535 unicode units long..
    // Have to split it if it is really that big
    protected static final int maxPlainTextLength = 60000;

    // move the building of the extension class name into a static initializer
    // in order to make it thread-safe (previously it was building every time
    // compile() was called.
    //
    static {
        try {
            extensionsClassnames.clear();
            extensionsClassnames.addAll( Play.pluginCollection.addTemplateExtensions());
            List<Class> extensionsClasses = Play.classloader.getAssignableClasses(JavaExtensions.class);
            for (Class extensionsClass : extensionsClasses) {
                extensionsClassnames.add(extensionsClass.getName());
            }
        } catch (Throwable e) {
            Logger.error(e, "unable to load class extensions!: %s", e.getMessage());
        }
    }

    final static Map<String,List<Pattern>> classToRegexMap;
    final static Map<String, String> originalNames = new HashMap<String, String>();

    static {
        classToRegexMap = new HashMap<String,List<Pattern>>();

        // Static access
        List<String> names = new ArrayList<String>();
        for (Class clazz : Play.classloader.getAllClasses()) {
            if (clazz.getName().endsWith("$")) {
                String name = clazz.getName().substring(0, clazz.getName().length() - 1).replace('$', '.') + '$';
                names.add(name);
                originalNames.put(name, clazz.getName());
            } else {
                String name = clazz.getName().replace('$', '.');
                names.add(name);
                originalNames.put(name, clazz.getName());
            }
        }
        Collections.sort(names, new Comparator<String>() {

            public int compare(String o1, String o2) {
                return o2.length() - o1.length();
            }
        });

        for (String cName : names) {
            classToRegexMap.put(cName, new ArrayList<Pattern>());
            classToRegexMap.get(cName).add(Pattern.compile("new " + Pattern.quote(cName) + "(\\([^)]*\\))"));
            classToRegexMap.get(cName).add(Pattern.compile("([a-zA-Z0-9.-_$]+)\\s+instanceof\\s+" + Pattern.quote(cName)));
            classToRegexMap.get(cName).add(Pattern.compile("([^.])" + Pattern.quote(cName) + ".class"));
            classToRegexMap.get(cName).add(Pattern.compile("([^'\".])" + Pattern.quote(cName) + "([.][^'\"])"));
        }
    }

    @Override
    String source() {
        String source = template.source;

        // If a plugin has something to change in the template before the compilation
        source = Play.pluginCollection.overrideTemplateSource(template, source);

        // We're about to do many many String.replaceAll() so we do some checking first
        // to try to reduce the number of needed replaceAll-calls.
        // Morten: I have tried to create a single regexp that can be used instead of all the replaceAll,
        // but I failed to do so.. Such a single regexp would be much faster since
        // we then we only would have to have one pass.

        if (!classToRegexMap.isEmpty()) {
            // Keep track of a dirty bit to see whether any of the first three
            // dynamic class binding rewrites were applied. If none of them
            // were used, skip the class name rewriting.
            //
            // All class names in LendUp templates were manually rewritten from
            //   com.foo.Foo
            // to
            //   _('com.foo.Foo')
            // so that we could avoid this step. Note that an inner class would
            // be rewritten to something like _('com.foo.Foo$Inner') instead.
            //
            // By skipping this step, we save (# of loaded classes) * (# of templates)
            // regex search-replaces. At the time of writing, that amounts to
            // about 3000 * 1200 ~ 3.6 million replaces.
            //
            // If the filename is of the form "{module:...}", let's assume that
            // we don't control it and apply the class name rewrite defensively.
            boolean dirty = template.name.startsWith("{module:");

            if (classToRegexMap.size() <= 1 || source.indexOf("new ")>=0) {
                String origSource = source;

                for (Entry<String,List<Pattern>> e : classToRegexMap.entrySet()) {
                    source = e.getValue().get(0).matcher(source).replaceAll("_('" + originalNames.get(e.getKey()).replace("$", "\\$") + "').newInstance$1");
                }

                dirty |= !origSource.equals(source);
            }

            if (classToRegexMap.size() <= 1 || source.indexOf("instanceof")>=0) {
                String origSource = source;

                for (Entry<String,List<Pattern>> e : classToRegexMap.entrySet()) {
                    source = e.getValue().get(1).matcher(source).replaceAll("_('" + originalNames.get(e.getKey()).replace("$", "\\$") + "').isAssignableFrom($1.class)");
                }

                dirty |= !origSource.equals(source);
            }

            if (classToRegexMap.size() <= 1 || source.indexOf(".class")>=0) {
                String origSource = source;

                for (Entry<String,List<Pattern>> e : classToRegexMap.entrySet()) {
                    source = e.getValue().get(2).matcher(source).replaceAll("$1_('" + originalNames.get(e.getKey()).replace("$", "\\$") + "')");

                }

                dirty |= !origSource.equals(source);
            }

            // We only want to remap class names if we encountered any of the previous
            // patterns (new keyword, .class, instanceof)
            if (dirty) {
                // With the current arg0 in replaceAll, it is not possible to do a quick indexOf-check for this one,
                // so we have to run all the replaceAll-calls
                for (Entry<String, List<Pattern>> e : classToRegexMap.entrySet()) {
                    source = e.getValue().get(3).matcher(source).replaceAll("$1_('" + originalNames.get(e.getKey()).replace("$", "\\$") + "')$2");
                }
            }
        }


        return source;
    }

    @Override
    void head() {
        print("class ");
        //This generated classname is parsed when creating cleanStackTrace.
        //The part after "Template_" is used as key when
        //looking up the file on disk this template-class is generated from.
        //cleanStackTrace is looking in TemplateLoader.templates

        String uniqueNumberForTemplateFile = TemplateLoader.getUniqueNumberForTemplateFile(template.name);

        String className = "Template_" + uniqueNumberForTemplateFile;
        print(className);
        println(" extends play.templates.GroovyTemplate.ExecutableTemplate {");
        println("public Object run() { use(play.templates.JavaExtensions) {");
        for (String n : extensionsClassnames) {
            println("use(_('" + n + "')) {");
        }
    }

    @Override
    @SuppressWarnings("unused")
    void end() {
        for (String n : extensionsClassnames) {
            println(" } ");
        }
        println("} }");
        println("}");
    }


    /**
     * Interesting performance observation:
     * Calling print(); from java (in ExecutableTemplate) called from groovy is MUCH slower than
     * java returning string to groovy
     * which then prints with out.print();
     */

    @Override
    void plain() {
        String text = parser.getToken().replace("\\", "\\\\").replaceAll("\"", "\\\\\"").replace("$", "\\$");
        if (skipLineBreak && text.startsWith("\n")) {
            text = text.substring(1);
        }
        skipLineBreak = false;
        text = text.replaceAll("\r\n", "\n").replaceAll("\n", "\\\\n");
        // we don't have to print line numbers here since this cannot fail - it is only text printing

        // [#714] The groovy-compiler complaints if a line is more than 65535 unicode units long..
        // Have to split it if it is really that big
        if (text.length() <maxPlainTextLength) {
            // text is "short" - just print it
            println("out.print(\""+text+"\");");
        } else {
            // text is long - must split it
            int offset = 0;
            do {
                int endPos = offset+maxPlainTextLength;
                if (endPos>text.length()) {
                    endPos = text.length();
                } else {
                    // #869 If the last char (at endPos-1) is \, we're dealing with escaped char - must include the next one also..
                    if ( text.charAt(endPos-1) == '\\') {
                        // use one more char so the escaping is not broken. Don't have to check length, since
                        // all '\' is used in escaping, ref replaceAll above..
                        endPos++;
                    }
                }
                println("out.print(\""+text.substring(offset, endPos)+"\");");
                offset+= (endPos - offset);
            }while(offset < text.length());
        }
    }

    @Override
    void script() {
        String text = parser.getToken();
        if (text.indexOf("\n") > -1) {
            String[] lines = parser.getToken().split("\n");
            for (int i = 0; i < lines.length; i++) {
                print(lines[i]);
                markLine(parser.getLine() + i);
                println();
            }
        } else {
            print(text);
            markLine(parser.getLine());
            println();
        }
        skipLineBreak = true;
    }

    @Override
    void expr() {
        String expr = parser.getToken().trim();
        print(";out.print(__safeFaster("+expr+"))");
        markLine(parser.getLine());
        println();
    }

    @Override
    void message() {
        String expr = parser.getToken().trim();
        print(";out.print(__getMessage("+expr+"))");
        markLine(parser.getLine());
        println();
    }

    @Override
    void action(boolean absolute) {
        String action = parser.getToken().trim();
        if (action.trim().matches("^'.*'$")) {
            if (absolute) {
                print("\tout.print(__reverseWithCheck_absolute_true("+action+"));");
            } else {
                print("\tout.print(__reverseWithCheck_absolute_false("+action+"));");
            }
        } else {
            if (!action.endsWith(")")) {
                action = action + "()";
            }
            if (absolute) {
                print("\tout.print(actionBridge._abs()." + action + ");");
            } else {
                print("\tout.print(actionBridge." + action + ");");
            }
        }
        markLine(parser.getLine());
        println();
    }

    @Override
    void startTag() {
        tagIndex++;
        String tagText = parser.getToken().trim().replaceAll("\r", "").replaceAll("\n", " ");
        String tagName = "";
        String tagArgs = "";
        boolean hasBody = !parser.checkNext().endsWith("/");
        if (tagText.indexOf(" ") > 0) {
            tagName = tagText.substring(0, tagText.indexOf(" "));
            tagArgs = tagText.substring(tagText.indexOf(" ") + 1).trim();
            if (!tagArgs.matches("^[_a-zA-Z0-9]+\\s*:.*$")) {
                tagArgs = "arg:" + tagArgs;
            }
            // We only have to try to replace the following if we find at least one
            // @ in tagArgs..
            if (tagArgs.indexOf('@')>=0) {
                tagArgs = tagArgs.replaceAll("[:]\\s*[@]{2}", ":actionBridge._abs().");
                tagArgs = tagArgs.replaceAll("(\\s)[@]{2}", "$1actionBridge._abs().");
                tagArgs = tagArgs.replaceAll("[:]\\s*[@]{1}", ":actionBridge.");
                tagArgs = tagArgs.replaceAll("(\\s)[@]{1}", "$1actionBridge.");
            }
        } else {
            tagName = tagText;
            tagArgs = ":";
        }
        Tag tag = new Tag();
        tag.name = tagName;
        tag.startLine = parser.getLine();
        tag.hasBody = hasBody;
        tagsStack.push(tag);
        if (tagArgs.trim().equals("_:_")) {
            print("attrs" + tagIndex + " = _attrs;");
        } else {
            print("attrs" + tagIndex + " = [" + tagArgs + "];");
        }
        // Use inlineTag if exists
        try {
            Method m = GroovyInlineTags.class.getDeclaredMethod("_" + tag.name, int.class, CALL.class);
            print("play.templates.TagContext.enterTag('" + tag.name + "');");
            print((String) m.invoke(null, new Object[]{tagIndex, CALL.START}));
            tag.hasBody = false;
            markLine(parser.getLine());
            println();
            skipLineBreak = true;
            return;
        } catch (Exception e) {
            // do nothing here
        }
        if (!tag.name.equals("doBody") && hasBody) {
            print("body" + tagIndex + " = {");
            markLine(parser.getLine());
            println();
        } else {
            print("body" + tagIndex + " = null;");
            markLine(parser.getLine());
            println();
        }
        skipLineBreak = true;

    }

    @Override
    void endTag() {
        String tagName = parser.getToken().trim();
        if (tagsStack.isEmpty()) {
            throw new TemplateCompilationException(template, parser.getLine(), "#{/" + tagName + "} is not opened.");
        }
        Tag tag = tagsStack.pop();
        String lastInStack = tag.name;
        if (tagName.equals("")) {
            tagName = lastInStack;
        }
        if (!lastInStack.equals(tagName)) {
            throw new TemplateCompilationException(template, tag.startLine, "#{" + tag.name + "} is not closed.");
        }
        if (tag.name.equals("doBody")) {
            print("if(_body || attrs" + tagIndex + "['body']) {");
            print("def toExecute = attrs" + tagIndex + "['body'] ?: _body; toUnset = []; if(attrs" + tagIndex + "['vars']) {");
            print("attrs" + tagIndex + "['vars'].each() {");
            print("if(toExecute.getProperty(it.key) == null) {toUnset.add(it.key);}; toExecute.setProperty(it.key, it.value);");
            print("}};");
            print("if(attrs" + tagIndex + "['as']) { setProperty(attrs" + tagIndex + "['as'], toExecute.toString()); } else { out.print(toExecute.toString()); }; toUnset.each() {toExecute.setProperty(it, null)} };");
            markLine(tag.startLine);
            template.doBodyLines.add(currentLine);
            println();
        } else {
            if (tag.hasBody) {
                print("};"); // close body closure
            }
            println();
            // Use inlineTag if exists
            try {
                Method m = GroovyInlineTags.class.getDeclaredMethod("_" + tag.name, int.class, CALL.class);
                println((String) m.invoke(null, new Object[]{tagIndex, CALL.END}));
                print("play.templates.TagContext.exitTag();");
            } catch (Exception e) {
                // Use fastTag if exists
                List<Class> fastClasses = new ArrayList<Class>();
                try {
                    fastClasses = Play.classloader.getAssignableClasses(FastTags.class);
                } catch (Exception xe) {
                    //
                }
                fastClasses.add(0, FastTags.class);
                Method m = null;
                String tName = tag.name;
                String tSpace = "";
                if (tName.indexOf(".") > 0) {
                    tSpace = tName.substring(0, tName.lastIndexOf("."));
                    tName = tName.substring(tName.lastIndexOf(".") + 1);
                }
                for (Class<?> c : fastClasses) {
                    if (!c.isAnnotationPresent(FastTags.Namespace.class) && tSpace.length() > 0) {
                        continue;
                    }
                    if (c.isAnnotationPresent(FastTags.Namespace.class) && !c.getAnnotation(FastTags.Namespace.class).value().equals(tSpace)) {
                        continue;
                    }
                    try {
                        m = c.getDeclaredMethod("_" + tName, Map.class, Closure.class, PrintWriter.class, GroovyTemplate.ExecutableTemplate.class, int.class);
                    } catch (NoSuchMethodException ex) {
                        continue;
                    }
                }
                if (m != null) {
                    print("play.templates.TagContext.enterTag('" + tag.name + "');");
                    print("_('" + m.getDeclaringClass().getName() + "')._" + tName + "(attrs" + tagIndex + ",body" + tagIndex + ", out, this, " + tag.startLine + ");");
                    print("play.templates.TagContext.exitTag();");
                } else {
                    print("invokeTag(" + tag.startLine + ",'" + tagName + "',attrs" + tagIndex + ",body" + tagIndex + ");");
                }
            }
            markLine(tag.startLine);
            println();
        }
        tagIndex--;
        skipLineBreak = true;
    }
}


