/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.lint.checks;

import static com.android.tools.lint.detector.api.LintConstants.ATTR_NAME;
import static com.android.tools.lint.detector.api.LintConstants.DOT_JAVA;
import static com.android.tools.lint.detector.api.LintConstants.TAG_STRING;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.resources.ResourceFolderType;
import com.android.tools.lint.client.api.IJavaParser;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Context;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.LintUtils;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Location.Handle;
import com.android.tools.lint.detector.api.Position;
import com.android.tools.lint.detector.api.ResourceXmlDetector;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.XmlContext;
import com.android.util.Pair;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.ast.AstVisitor;
import lombok.ast.CharLiteral;
import lombok.ast.Expression;
import lombok.ast.FloatingPointLiteral;
import lombok.ast.ForwardingAstVisitor;
import lombok.ast.IntegralLiteral;
import lombok.ast.MethodDeclaration;
import lombok.ast.MethodInvocation;
import lombok.ast.NullLiteral;
import lombok.ast.Select;
import lombok.ast.StrictListAccessor;
import lombok.ast.StringLiteral;
import lombok.ast.VariableDefinition;
import lombok.ast.VariableDefinitionEntry;
import lombok.ast.VariableReference;

/**
 * Check which looks for problems with formatting strings such as inconsistencies between
 * translations or between string declaration and string usage in Java.
 */
public class StringFormatDetector extends ResourceXmlDetector implements Detector.JavaScanner {
    /** The name of the String.format method */
    private static final String FORMAT_METHOD = "format"; //$NON-NLS-1$

    /** Whether formatting strings are invalid */
    public static final Issue INVALID = Issue.create(
            "StringFormatInvalid", //$NON-NLS-1$
            "Checks that format strings are valid",

            "If a string contains a '%' character, then the string may be a formatting string " +
            "which will be passed to String.format from Java code to replace each '%' " +
            "occurrence with specific values.\n" +
            "\n" +
            "This lint warning checks for two related problems:\n" +
            "(1) Formatting strings that are invalid, meaning that String.format will throw " +
            "exceptions at runtime when attempting to use the format string.\n" +
            "(2) Strings containing '%' that are not formatting strings getting passed to " +
            "a String.format call. In this case the '%' will need to be escaped as '%%'.\n" +
            "\n" +
            "NOTE: Not all Strings which look like formatting strings are intended for " +
            "use by String.format; for example, they may contain date formats intended " +
            "for android.text.format.Time#format(). Lint cannot always figure out that " +
            "a String is a date format, so you may get false warnings in those scenarios. " +
            "See the suppress help topic for information on how to suppress errors in " +
            "that case.",

            Category.MESSAGES,
            9,
            Severity.ERROR,
            StringFormatDetector.class,
            Scope.ALL_RESOURCES_SCOPE);

    /** Whether formatting argument types are consistent across translations */
    public static final Issue ARG_COUNT = Issue.create(
            "StringFormatCount", //$NON-NLS-1$
            "Ensures that all format strings are used and that the same number is defined "
                + "across translations",

            "When a formatted string takes arguments, it usually needs to reference the " +
            "same arguments in all translations. There are cases where this is not the case, " +
            "so this issue is a warning rather than an error by default. However, this usually " +
            "happens when a language is not translated or updated correctly.",
            Category.MESSAGES,
            5,
            Severity.WARNING,
            StringFormatDetector.class,
            Scope.ALL_RESOURCES_SCOPE);

    /** Whether the string format supplied in a call to String.format matches the format string */
    public static final Issue ARG_TYPES = Issue.create(
            "StringFormatMatches", //$NON-NLS-1$
            "Ensures that the format used in <string> definitions is compatible with the "
                + "String.format call",

            "This lint check ensures the following:\n" +
            "(1) If there are multiple translations of the format string, then all translations " +
            "use the same type for the same numbered arguments\n" +
            "(2) The usage of the format string in Java is consistent with the format string, " +
            "meaning that the parameter types passed to String.format matches those in the " +
            "format string.",
            Category.MESSAGES,
            9,
            Severity.ERROR,
            StringFormatDetector.class,
            EnumSet.of(Scope.ALL_RESOURCE_FILES, Scope.JAVA_FILE));

    /**
     * Map from a format string name to a list of declaration file and actual
     * formatting string content. We're using a list since a format string can be
     * defined multiple times, usually for different translations.
     */
    private Map<String, List<Pair<Handle, String>>> mFormatStrings;

    /**
     * List of strings that contain percents that aren't formatting strings; these
     * should not be passed to String.format.
     */
    private Map<String, Handle> mNotFormatStrings = new HashMap<String, Handle>();

    /** Constructs a new {@link StringFormatDetector} check */
    public StringFormatDetector() {
    }

    @Override
    public boolean appliesTo(@NonNull ResourceFolderType folderType) {
        return folderType == ResourceFolderType.VALUES;
    }

    @Override
    public boolean appliesTo(@NonNull Context context, @NonNull File file) {
        if (LintUtils.endsWith(file.getName(), DOT_JAVA)) {
            return mFormatStrings != null;
        }

        return super.appliesTo(context, file);
    }

    @Override
    public Collection<String> getApplicableElements() {
        return Collections.singletonList(TAG_STRING);
    }

    @Override
    public void visitElement(@NonNull XmlContext context, @NonNull Element element) {
        NodeList childNodes = element.getChildNodes();
        if (childNodes.getLength() > 0) {
            if (childNodes.getLength() == 1) {
                Node child = childNodes.item(0);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    checkTextNode(context, element, strip(child.getNodeValue()));
                }
            } else {
                // Concatenate children and build up a plain string.
                // This is needed to handle xliff localization documents,
                // but this needs more work so ignore compound XML documents as
                // string values for now:
                //StringBuilder sb = new StringBuilder();
                //addText(sb, element);
                //if (sb.length() > 0) {
                //    checkTextNode(context, element, sb.toString());
                //}
            }
        }
    }

    //private static void addText(StringBuilder sb, Node node) {
    //    if (node.getNodeType() == Node.TEXT_NODE) {
    //        sb.append(strip(node.getNodeValue().trim()));
    //    } else {
    //        NodeList childNodes = node.getChildNodes();
    //        for (int i = 0, n = childNodes.getLength(); i < n; i++) {
    //            addText(sb, childNodes.item(i));
    //        }
    //    }
    //}

    private static String strip(String s) {
        if (s.length() < 2) {
            return s;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (first == last && (first == '\'' || first == '"')) {
            return s.substring(1, s.length() - 1);
        }

        return s;
    }

    private void checkTextNode(XmlContext context, Element element, String text) {
        String name = null;
        boolean found = false;

        // Look at the String and see if it's a format string (contains
        // positional %'s)
        for (int j = 0, m = text.length(); j < m; j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                j++;
            }
            if (c == '%') {
                if (name == null) {
                    name = element.getAttribute(ATTR_NAME);
                }

                // Also make sure this String isn't an unformatted String
                String formatted = element.getAttribute("formatted"); //$NON-NLS-1$
                if (formatted.length() > 0 && !Boolean.parseBoolean(formatted)) {
                    if (!mNotFormatStrings.containsKey(name)) {
                        Handle handle = context.parser.createLocationHandle(context, element);
                        handle.setClientData(element);
                        mNotFormatStrings.put(name, handle);
                    }
                    return;
                }

                // See if it's not a format string, e.g. "Battery charge is 100%!".
                // If so we want to record this name in a special list such that we can
                // make sure you don't attempt to reference this string from a String.format
                // call.
                Matcher matcher = FORMAT.matcher(text);
                if (!matcher.find(j)) {
                    if (!mNotFormatStrings.containsKey(name)) {
                        Handle handle = context.parser.createLocationHandle(context, element);
                        handle.setClientData(element);
                        mNotFormatStrings.put(name, handle);
                    }
                    return;
                }

                String conversion = matcher.group(6);
                int conversionClass = getConversionClass(conversion.charAt(0));
                if (conversionClass == CONVERSION_CLASS_UNKNOWN || matcher.group(5) != null) {
                    if (!mNotFormatStrings.containsKey(name)) {
                        Handle handle = context.parser.createLocationHandle(context, element);
                        handle.setClientData(element);
                        mNotFormatStrings.put(name, handle);
                    }
                    // Don't process any other strings here; some of them could
                    // accidentally look like a string, e.g. "%H" is a hash code conversion
                    // in String.format (and hour in Time formatting).
                    return;
                }

                found = true;
            }
        }

        if (found && name != null) {
            // Record it for analysis when seen in Java code
            if (mFormatStrings == null) {
                mFormatStrings = new HashMap<String, List<Pair<Handle,String>>>();
            }

            List<Pair<Handle, String>> list = mFormatStrings.get(name);
            if (list == null) {
                list = new ArrayList<Pair<Handle, String>>();
                mFormatStrings.put(name, list);
            }
            Handle handle = context.parser.createLocationHandle(context, element);
            handle.setClientData(element);
            list.add(Pair.of(handle, text));
        }
    }

    @Override
    public void afterCheckProject(@NonNull Context context) {
        if (mFormatStrings != null) {
            Formatter formatter = new Formatter();

            boolean checkCount = context.isEnabled(ARG_COUNT);
            boolean checkValid = context.isEnabled(INVALID);
            boolean checkTypes = context.isEnabled(ARG_TYPES);

            // Ensure that all the format strings are consistent with respect to each other;
            // e.g. they all have the same number of arguments, they all use all the
            // arguments, and they all use the same types for all the numbered arguments
            for (Map.Entry<String, List<Pair<Handle, String>>> entry : mFormatStrings.entrySet()) {
                String name = entry.getKey();
                List<Pair<Handle, String>> list = entry.getValue();

                // Check argument counts
                if (checkCount) {
                    checkArity(context, name, list);
                }

                // Check argument types (and also make sure that the formatting strings are valid)
                if (checkValid || checkTypes) {
                    checkTypes(context, formatter, checkValid, checkTypes, name, list);
                }
            }

            formatter.close();
        }
    }

    private void checkTypes(Context context, Formatter formatter, boolean checkValid,
            boolean checkTypes, String name, List<Pair<Handle, String>> list) {
        Map<Integer, String> types = new HashMap<Integer, String>();
        Map<Integer, Handle> typeDefinition = new HashMap<Integer, Handle>();
        for (Pair<Handle, String> pair : list) {
            Handle handle = pair.getFirst();
            String formatString = pair.getSecond();

            //boolean warned = false;
            Matcher matcher = FORMAT.matcher(formatString);
            int index = 0;
            int prevIndex = 0;
            int nextNumber = 1;
            while (true) {
                if (matcher.find(index)) {
                    int matchStart = matcher.start();
                    // Make sure this is not an escaped '%'
                    for (; prevIndex < matchStart; prevIndex++) {
                        char c = formatString.charAt(prevIndex);
                        if (c == '\\') {
                            prevIndex++;
                        }
                    }
                    if (prevIndex > matchStart) {
                        // We're in an escape, ignore this result
                        index = prevIndex;
                        continue;
                    }

                    index = matcher.end(); // Ensure loop proceeds
                    String str = formatString.substring(matchStart, matcher.end());
                    if (str.equals("%%")) { //$NON-NLS-1$
                        // Just an escaped %
                        continue;
                    }

                    if (checkValid) {
                        // Make sure it's a valid format string
                        if (str.length() > 2 && str.charAt(str.length() - 2) == ' ') {
                            char last = str.charAt(str.length() - 1);
                            // If you forget to include the conversion character, e.g.
                            //   "Weight=%1$ g" instead of "Weight=%1$d g", then
                            // you're going to end up with a format string interpreted as
                            // "%1$ g". This means that the space character is interpreted
                            // as a flag character, but it can only be a flag character
                            // when used in conjunction with the numeric conversion
                            // formats (d, o, x, X). If that's not the case, make a
                            // dedicated error message
                            if (last != 'd' && last != 'o' && last != 'x' && last != 'X') {
                                Object clientData = handle.getClientData();
                                if (clientData instanceof Node) {
                                    if (context.getDriver().isSuppressed(INVALID,
                                            (Node) clientData)) {
                                        return;
                                    }
                                }

                                Location location = handle.resolve();
                                String message = String.format(
                                        "Incorrect formatting string %1$s; missing conversion " +
                                        "character in '%2$s' ?", name, str);
                                context.report(INVALID, location, message, null);
                                //warned = true;
                                continue;
                            }
                        }
                    }

                    if (!checkTypes) {
                        continue;
                    }

                    // Shouldn't throw a number format exception since we've already
                    // matched the pattern in the regexp
                    int number;
                    String numberString = matcher.group(1);
                    if (numberString != null) {
                        // Strip off trailing $
                        numberString = numberString.substring(0, numberString.length() - 1);
                        number = Integer.parseInt(numberString);
                        nextNumber = number + 1;
                    } else {
                        number = nextNumber++;
                    }
                    String format = matcher.group(6);
                    String currentFormat = types.get(number);
                    if (currentFormat == null) {
                        types.put(number, format);
                        typeDefinition.put(number, handle);
                    } else if (!currentFormat.equals(format)
                            && isIncompatible(currentFormat.charAt(0), format.charAt(0))) {

                        Object clientData = handle.getClientData();
                        if (clientData instanceof Node) {
                            if (context.getDriver().isSuppressed(ARG_TYPES, (Node) clientData)) {
                                return;
                            }
                        }

                        Location location = handle.resolve();
                        // Attempt to limit the location range to just the formatting
                        // string in question
                        location = refineLocation(context, location, formatString,
                                matcher.start(), matcher.end());
                        Location otherLocation = typeDefinition.get(number).resolve();
                        otherLocation.setMessage("Conflicting argument type here");
                        location.setSecondary(otherLocation);
                        File f = otherLocation.getFile();
                        String message = String.format(
                                "Inconsistent formatting types for argument #%1$d in " +
                                "format string %2$s ('%3$s'): Found both '%4$s' and '%5$s' " +
                                "(in %6$s)",
                                number, name,
                                str,
                                currentFormat, format,
                                f.getParentFile().getName() + File.separator + f.getName());
                        //warned = true;
                        context.report(ARG_TYPES, location, message, null);
                        break;
                    }
                } else {
                    break;
                }
            }

            // Check that the format string is valid by actually attempting to instantiate
            // it. We only do this if we haven't already complained about this string
            // for other reasons.
            /* Check disabled for now: it had many false reports due to conversion
             * errors (which is expected since we just pass in strings), but once those
             * are eliminated there aren't really any other valid error messages returned
             * (for example, calling the formatter with bogus formatting flags always just
             * returns a "conversion" error. It looks like we'd need to actually pass compatible
             * arguments to trigger other types of formatting errors such as precision errors.
            if (!warned && checkValid) {
                try {
                    formatter.format(formatString, "", "", "", "", "", "", "",
                            "", "", "", "", "", "", "");

                } catch (IllegalFormatException t) { // TODO: UnknownFormatConversionException
                    if (!t.getLocalizedMessage().contains(" != ")
                            && !t.getLocalizedMessage().contains("Conversion")) {
                        Location location = handle.resolve();
                        context.report(INVALID, location,
                                String.format("Wrong format for %1$s: %2$s",
                                        name, t.getLocalizedMessage()), null);
                    }
                }
            }
            */
        }
    }

    /**
     * Returns true if two String.format conversions are "incompatible" (meaning
     * that using these two for the same argument across different translations
     * is more likely an error than intentional. Some conversions are
     * incompatible, e.g. "d" and "s" where one is a number and string, whereas
     * others may work (e.g. float versus integer) but are probably not
     * intentional.
     */
    private boolean isIncompatible(char conversion1, char conversion2) {
        int class1 = getConversionClass(conversion1);
        int class2 = getConversionClass(conversion2);
        return class1 != class2
                && class1 != CONVERSION_CLASS_UNKNOWN
                && class2 != CONVERSION_CLASS_UNKNOWN;
    }

    private static final int CONVERSION_CLASS_UNKNOWN = 0;
    private static final int CONVERSION_CLASS_STRING = 1;
    private static final int CONVERSION_CLASS_CHARACTER = 2;
    private static final int CONVERSION_CLASS_INTEGER = 3;
    private static final int CONVERSION_CLASS_FLOAT = 4;
    private static final int CONVERSION_CLASS_BOOLEAN = 5;
    private static final int CONVERSION_CLASS_HASHCODE = 6;
    private static final int CONVERSION_CLASS_PERCENT = 7;
    private static final int CONVERSION_CLASS_NEWLINE = 8;
    private static final int CONVERSION_CLASS_DATETIME = 9;

    private static int getConversionClass(char conversion) {
        // See http://developer.android.com/reference/java/util/Formatter.html
        switch (conversion) {
            case 't':   // Time/date conversion
            case 'T':
                return CONVERSION_CLASS_DATETIME;
            case 's':   // string
            case 'S':   // Uppercase string
                return CONVERSION_CLASS_STRING;
            case 'c':   // character
            case 'C':   // Uppercase character
                return CONVERSION_CLASS_CHARACTER;
            case 'd':   // decimal
            case 'o':   // octal
            case 'x':   // hex
            case 'X':
                return CONVERSION_CLASS_INTEGER;
            case 'f':   // decimal float
            case 'e':   // exponential float
            case 'E':
            case 'g':   // decimal or exponential depending on size
            case 'G':
            case 'a':   // hex float
            case 'A':
                return CONVERSION_CLASS_FLOAT;
            case 'b':   // boolean
            case 'B':
                return CONVERSION_CLASS_BOOLEAN;
            case 'h':   // boolean
            case 'H':
                return CONVERSION_CLASS_HASHCODE;
            case '%':   // literal
                return CONVERSION_CLASS_PERCENT;
            case 'n':   // literal
                return CONVERSION_CLASS_NEWLINE;
        }

        return CONVERSION_CLASS_UNKNOWN;
    }

    private Location refineLocation(Context context, Location location, String formatString,
            int substringStart, int substringEnd) {
        Position startLocation = location.getStart();
        Position endLocation = location.getStart();
        if (startLocation != null && endLocation != null) {
            int startOffset = startLocation.getOffset();
            int endOffset = endLocation.getOffset();
            if (startOffset >= 0) {
                String contents = context.getClient().readFile(location.getFile());
                if (contents != null
                        && endOffset <= contents.length() && startOffset < endOffset) {
                    int formatOffset = contents.indexOf(formatString, startOffset);
                    if (formatOffset != -1 && formatOffset <= endOffset) {
                        return Location.create(context.file, contents,
                                formatOffset + substringStart, formatOffset + substringEnd);
                    }
                }
            }
        }

        return location;
    }

    /**
     * Check that the number of arguments in the format string is consistent
     * across translations, and that all arguments are used
     */
    private void checkArity(Context context, String name, List<Pair<Handle, String>> list) {
        // Check to make sure that the argument counts and types are consistent
        int prevCount = -1;
        for (Pair<Handle, String> pair : list) {
            Set<Integer> indices = new HashSet<Integer>();
            int count = getFormatArgumentCount(pair.getSecond(), indices);
            Handle handle = pair.getFirst();
            if (prevCount != -1 && prevCount != count) {
                Object clientData = handle.getClientData();
                if (clientData instanceof Node) {
                    if (context.getDriver().isSuppressed(ARG_COUNT, (Node) clientData)) {
                        return;
                    }
                }
                Location location = handle.resolve();
                Location secondary = list.get(0).getFirst().resolve();
                secondary.setMessage("Conflicting number of arguments here");
                location.setSecondary(secondary);
                String message = String.format(
                        "Inconsistent number of arguments in formatting string %1$s; " +
                        "found both %2$d and %3$d", name, prevCount, count);
                context.report(ARG_COUNT, location, message, null);
                break;
            }

            for (int i = 1; i <= count; i++) {
                if (!indices.contains(i)) {
                    Object clientData = handle.getClientData();
                    if (clientData instanceof Node) {
                        if (context.getDriver().isSuppressed(ARG_COUNT, (Node) clientData)) {
                            return;
                        }
                    }

                    Set<Integer> all = new HashSet<Integer>();
                    for (int j = 1; j < count; j++) {
                        all.add(j);
                    }
                    all.removeAll(indices);
                    List<Integer> sorted = new ArrayList<Integer>(all);
                    Collections.sort(sorted);
                    Location location = handle.resolve();
                    String message = String.format(
                            "Formatting string '%1$s' is not referencing numbered arguments %2$s",
                            name, sorted);
                    context.report(ARG_COUNT, location, message, null);
                    break;
                }
            }

            prevCount = count;
        }
    }

    // See java.util.Formatter docs
    private static final Pattern FORMAT = Pattern.compile(
            // Generic format:
            //   %[argument_index$][flags][width][.precision]conversion
            //
            "%" +                                                               //$NON-NLS-1$
            // Argument Index
            "(\\d+\\$)?" +                                                      //$NON-NLS-1$
            // Flags
            "([-+#, 0(\\<]*)?" +                                                //$NON-NLS-1$
            // Width
            "(\\d+)?" +                                                         //$NON-NLS-1$
            // Precision
            "(\\.\\d+)?" +                                                      //$NON-NLS-1$
            // Conversion. These are all a single character, except date/time conversions
            // which take a prefix of t/T:
            "([tT])?" +                                                         //$NON-NLS-1$
            // The current set of conversion characters are
            // b,h,s,c,d,o,x,e,f,g,a,t (as well as all those as upper-case characters), plus
            // n for newlines and % as a literal %. And then there are all the time/date
            // characters: HIKLm etc. Just match on all characters here since there should
            // be at least one.
            "([a-zA-Z%])");                                                     //$NON-NLS-1$

    /** Given a format string returns the format type of the given argument */
    @VisibleForTesting
    static String getFormatArgumentType(String s, int argument) {
        Matcher matcher = FORMAT.matcher(s);
        int index = 0;
        int prevIndex = 0;
        int nextNumber = 1;
        while (true) {
            if (matcher.find(index)) {
                int matchStart = matcher.start();
                // Make sure this is not an escaped '%'
                for (; prevIndex < matchStart; prevIndex++) {
                    char c = s.charAt(prevIndex);
                    if (c == '\\') {
                        prevIndex++;
                    }
                }
                if (prevIndex > matchStart) {
                    // We're in an escape, ignore this result
                    index = prevIndex;
                    continue;
                }

                // Shouldn't throw a number format exception since we've already
                // matched the pattern in the regexp
                int number;
                String numberString = matcher.group(1);
                if (numberString != null) {
                    // Strip off trailing $
                    numberString = numberString.substring(0, numberString.length() - 1);
                    number = Integer.parseInt(numberString);
                    nextNumber = number + 1;
                } else {
                    number = nextNumber++;
                }

                if (number == argument) {
                    return matcher.group(6);
                }
                index = matcher.end();
            } else {
                break;
            }
        }

        return null;
    }

    /**
     * Given a format string returns the number of required arguments. If the
     * {@code seenArguments} parameter is not null, put the indices of any
     * observed arguments into it.
     */
    @VisibleForTesting
    static int getFormatArgumentCount(String s, Set<Integer> seenArguments) {
        Matcher matcher = FORMAT.matcher(s);
        int index = 0;
        int prevIndex = 0;
        int nextNumber = 1;
        int max = 0;
        while (true) {
            if (matcher.find(index)) {
                int matchStart = matcher.start();
                // Make sure this is not an escaped '%'
                for (; prevIndex < matchStart; prevIndex++) {
                    char c = s.charAt(prevIndex);
                    if (c == '\\') {
                        prevIndex++;
                    }
                }
                if (prevIndex > matchStart) {
                    // We're in an escape, ignore this result
                    index = prevIndex;
                    continue;
                }

                // Shouldn't throw a number format exception since we've already
                // matched the pattern in the regexp
                int number;
                String numberString = matcher.group(1);
                if (numberString != null) {
                    // Strip off trailing $
                    numberString = numberString.substring(0, numberString.length() - 1);
                    number = Integer.parseInt(numberString);
                    nextNumber = number + 1;
                } else {
                    number = nextNumber++;
                }

                if (number > max) {
                    max = number;
                }
                if (seenArguments != null) {
                    seenArguments.add(number);
                }

                index = matcher.end();
            } else {
                break;
            }
        }

        return max;
    }

    @Override
    public List<String> getApplicableMethodNames() {
        return Collections.singletonList(FORMAT_METHOD);
    }

    @Override
    public void visitMethod(@NonNull JavaContext context, @Nullable AstVisitor visitor,
            @NonNull MethodInvocation node) {
        if (mFormatStrings == null) {
            return;
        }

        String methodName = node.astName().getDescription();
        assert methodName.equals(FORMAT_METHOD);
        if (node.astOperand() instanceof VariableReference) {
            VariableReference ref = (VariableReference) node.astOperand();
            if ("String".equals(ref.astIdentifier().astValue())) { //$NON-NLS-1$
                // Found a String.format call
                // Look inside to see if we can find an R string
                // Find surrounding method
                lombok.ast.Node current = node.getParent();
                while (current != null && !(current instanceof MethodDeclaration)) {
                    current = current.getParent();
                }
                if (current instanceof MethodDeclaration) {
                    checkStringFormatCall(context, (MethodDeclaration) current, node);
                }
            }
        }
    }

    /**
     * Check the given String.format call (with the given arguments) to see if
     * the string format is being used correctly
     *
     * @param context the context to report errors to
     * @param method the method containing the {@link String#format} call
     * @param call the AST node for the {@link String#format}
     */
    private void checkStringFormatCall(
            JavaContext context,
            MethodDeclaration method,
            MethodInvocation call) {

        StrictListAccessor<Expression, MethodInvocation> args = call.astArguments();
        if (args.size() == 0) {
            return;
        }

        StringTracker tracker = new StringTracker(method, call);
        method.accept(tracker);
        String name = tracker.getFormatStringName();
        if (name == null) {
            return;
        }

        if (mNotFormatStrings.containsKey(name)) {
            Handle handle = mNotFormatStrings.get(name);
            Object clientData = handle.getClientData();
            if (clientData instanceof Node) {
                if (context.getDriver().isSuppressed(INVALID, (Node) clientData)) {
                    return;
                }
            }
            Location location = handle.resolve();
            String message = String.format(
                    "Format string '%1$s' is not a valid format string so it should not be " +
                    "passed to String.format",
                    name);
            context.report(INVALID, location, message, null);
            return;
        }

        List<Pair<Handle, String>> list = mFormatStrings.get(name);
        if (list != null) {
            for (Pair<Handle, String> pair : list) {
                String s = pair.getSecond();
                int count = getFormatArgumentCount(s, null);
                Handle handle = pair.getFirst();
                if (count != args.size() - 1) {
                    Location location = context.parser.getLocation(context, call);
                    Location secondary = handle.resolve();
                    secondary.setMessage(String.format("This definition requires %1$d arguments",
                            count));
                    location.setSecondary(secondary);
                    String message = String.format(
                            "Wrong argument count, format string %1$s requires %2$d but format " +
                            "call supplies %3$d",
                            name, count, args.size() - 1);
                    context.report(ARG_TYPES, method, location, message, null);
                } else {
                    for (int i = 1; i <= count; i++) {
                        Class<?> type = tracker.getArgumentType(i);
                        if (type != null) {
                            boolean valid = true;
                            String formatType = getFormatArgumentType(s, i);
                            char last = formatType.charAt(formatType.length() - 1);
                            if (formatType.length() >= 2 &&
                                    Character.toLowerCase(
                                            formatType.charAt(formatType.length() - 2)) == 't') {
                                // Date time conversion.
                                // TODO
                                continue;
                            }
                            switch (last) {
                                // Booleans. It's okay to pass objects to these;
                                // it will print "true" if non-null, but it's
                                // unusual and probably not intended.
                                case 'b':
                                case 'B':
                                    valid = type == Boolean.TYPE;
                                    break;

                                // Numeric: integer and floats in various formats
                                case 'x':
                                case 'X':
                                case 'd':
                                case 'o':
                                case 'e':
                                case 'E':
                                case 'f':
                                case 'g':
                                case 'G':
                                case 'a':
                                case 'A':
                                    valid = type == Integer.TYPE
                                            || type == Float.TYPE;
                                    break;
                                case 'c':
                                case 'C':
                                    // Unicode character
                                    valid = type == Character.TYPE;
                                    break;
                                case 'h':
                                case 'H': // Hex print of hash code of objects
                                case 's':
                                case 'S':
                                    // String. Can pass anything, but warn about
                                    // numbers since you may have meant more
                                    // specific formatting. Use special issue
                                    // explanation for this?
                                    valid = type != Boolean.TYPE &&
                                        !type.isAssignableFrom(Number.class);
                                    break;
                            }

                            if (!valid) {
                                IJavaParser parser = context.parser;
                                Expression argument = tracker.getArgument(i);
                                Location location = parser.getLocation(context, argument);
                                Location secondary = handle.resolve();
                                secondary.setMessage("Conflicting argument declaration here");
                                location.setSecondary(secondary);

                                String message = String.format(
                                        "Wrong argument type for formatting argument '#%1$d' " +
                                        "in %2$s: conversion is '%3$s', received %4$s",
                                        i, name, formatType, type.getSimpleName());
                                context.report(ARG_TYPES, method, location, message, null);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Given a variable reference, finds the original R.string value corresponding to it.
     * For example:
     * <pre>
     * {@code
     *  String target = "World";
     *  String hello = getResources().getString(R.string.hello);
     *  String output = String.format(hello, target);
     * }
     * </pre>
     *
     * Given the {@code String.format} call, we want to find out what R.string resource
     * corresponds to the first argument, in this case {@code R.string.hello}.
     * To do this, we look for R.string references, and track those through assignments
     * until we reach the target node.
     * <p>
     * In addition, it also does some primitive type tracking such that it (in some cases)
     * can answer questions about the types of variables. This allows it to check whether
     * certain argument types are valid. Note however that it does not do full-blown
     * type analysis by checking method call signatures and so on.
     */
    private static class StringTracker extends ForwardingAstVisitor {
        /** Method we're searching within */
        private final MethodDeclaration mTop;
        /** Map from variable name to corresponding string resource name */
        private final Map<String, String> mMap = new HashMap<String, String>();
        /** Map from variable name to corresponding type */
        private final Map<String, Class<?>> mTypes = new HashMap<String, Class<?>>();
        /** The AST node for the String.format we're interested in */
        private MethodInvocation mTargetNode;
        private boolean mDone;
        /**
         * Result: the name of the string resource being passed to the
         * String.format, if any
         */
        private String mName;

        public StringTracker(MethodDeclaration top, MethodInvocation targetNode) {
            mTop = top;
            mTargetNode = targetNode;
        }

        public String getFormatStringName() {
            return mName;
        }

        /** Returns the argument type of the given formatting argument of the
         * target node. Note: This is in the formatting string, which is one higher
         * than the String.format parameter number, since the first argument is the
         * formatting string itself.
         *
         * @param argument the argument number
         * @return the class (such as {@link Integer#TYPE} etc) or null if not known
         */
        public Class<?> getArgumentType(int argument) {
            Expression arg = getArgument(argument);
            if (arg != null) {
                Class<?> type = getType(arg);
                if (type != null) {
                    return type;
                }
            }

            return null;
        }

        public Expression getArgument(int argument) {
            StrictListAccessor<Expression, MethodInvocation> args = mTargetNode.astArguments();
            if (argument >= args.size()) {
                return null;
            }

            Iterator<Expression> iterator = args.iterator();
            int index = 0;
            while (iterator.hasNext()) {
                Expression arg = iterator.next();
                if (index++ == argument) {
                    return arg;
                }
            }

            return null;
        }

        @Override
        public boolean visitNode(lombok.ast.Node node) {
            if (mDone) {
                return true;
            }

            return super.visitNode(node);
        }

        @Override
        public boolean visitVariableReference(VariableReference node) {
            if (node.astIdentifier().getDescription().equals("R") &&   //$NON-NLS-1$
                    node.getParent() instanceof Select &&
                    node.getParent().getParent() instanceof Select) {

                // See if we're on the right hand side of an assignment
                lombok.ast.Node current = node.getParent().getParent();
                String reference = ((Select) current).astIdentifier().astValue();

                while (current != mTop && !(current instanceof VariableDefinitionEntry)) {
                    current = current.getParent();
                }
                if (current instanceof VariableDefinitionEntry) {
                    VariableDefinitionEntry entry = (VariableDefinitionEntry) current;
                    String variable = entry.astName().astValue();
                    mMap.put(variable, reference);
                }
            }

            return false;
        }

        @Override
        public boolean visitMethodInvocation(MethodInvocation node) {
            if (node == mTargetNode) {
                StrictListAccessor<Expression, MethodInvocation> args = node.astArguments();
                if (args.size() > 0) {
                    Expression first = args.first();
                    if (first instanceof VariableReference) {
                          VariableReference reference = (VariableReference) first;
                          String variable = reference.astIdentifier().astValue();
                          mName = mMap.get(variable);
                          mDone = true;
                          return true;
                    }
                }
            }

            // Is this a getString() call? On a resource object? If so,
            // promote the resource argument up to the left hand side
            return super.visitMethodInvocation(node);
        }

        @Override
        public boolean visitVariableDefinitionEntry(VariableDefinitionEntry node) {
            String name = node.astName().astValue();
            Expression rhs = node.astInitializer();
            Class<?> type = getType(rhs);
            if (type != null) {
                mTypes.put(name, type);
            } else {
                // Make sure we're not visiting the String.format node itself. If you have
                //    msg = String.format("%1$s", msg)
                // then we'd be wiping out the type of "msg" before visiting the
                // String.format call!
                if (rhs != mTargetNode) {
                    mTypes.remove(name);
                }
            }

            return super.visitVariableDefinitionEntry(node);
        }

        private Class<?> getType(Expression expression) {
            if (expression instanceof VariableReference) {
                VariableReference reference = (VariableReference) expression;
                String variable = reference.astIdentifier().astValue();
                return mTypes.get(variable);
            } else if (expression instanceof MethodInvocation) {
                MethodInvocation method = (MethodInvocation) expression;
                String methodName = method.astName().astValue();
                if (methodName.equals("getString")) { //$NON-NLS-1$
                    return String.class;
                }
            } else if (expression instanceof StringLiteral) {
                return String.class;
            } else if (expression instanceof IntegralLiteral) {
                return Integer.TYPE;
            } else if (expression instanceof FloatingPointLiteral) {
                return Float.TYPE;
            } else if (expression instanceof CharLiteral) {
                return Character.TYPE;
            } else if (expression instanceof NullLiteral) {
                return Object.class;
            }

            return null;
        }

        @Override
        public boolean visitVariableDefinition(VariableDefinition node) {
            // TODO Auto-generated method stub
            return super.visitVariableDefinition(node);
        }
    }
}
