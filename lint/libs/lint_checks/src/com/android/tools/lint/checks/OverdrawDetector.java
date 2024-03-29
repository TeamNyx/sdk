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

import static com.android.tools.lint.detector.api.LintConstants.ANDROID_URI;
import static com.android.tools.lint.detector.api.LintConstants.ATTR_BACKGROUND;
import static com.android.tools.lint.detector.api.LintConstants.ATTR_NAME;
import static com.android.tools.lint.detector.api.LintConstants.ATTR_PARENT;
import static com.android.tools.lint.detector.api.LintConstants.ATTR_THEME;
import static com.android.tools.lint.detector.api.LintConstants.ATTR_TILE_MODE;
import static com.android.tools.lint.detector.api.LintConstants.DOT_JAVA;
import static com.android.tools.lint.detector.api.LintConstants.DOT_XML;
import static com.android.tools.lint.detector.api.LintConstants.DRAWABLE_RESOURCE_PREFIX;
import static com.android.tools.lint.detector.api.LintConstants.NULL_RESOURCE;
import static com.android.tools.lint.detector.api.LintConstants.STYLE_RESOURCE_PREFIX;
import static com.android.tools.lint.detector.api.LintConstants.TAG_ACTIVITY;
import static com.android.tools.lint.detector.api.LintConstants.TAG_APPLICATION;
import static com.android.tools.lint.detector.api.LintConstants.TAG_BITMAP;
import static com.android.tools.lint.detector.api.LintConstants.TAG_STYLE;
import static com.android.tools.lint.detector.api.LintConstants.TRANSPARENT_COLOR;
import static com.android.tools.lint.detector.api.LintConstants.VALUE_DISABLED;
import static com.android.tools.lint.detector.api.LintUtils.endsWith;

import com.android.annotations.NonNull;
import com.android.resources.ResourceFolderType;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Context;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.LayoutDetector;
import com.android.tools.lint.detector.api.LintUtils;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Project;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.Speed;
import com.android.tools.lint.detector.api.XmlContext;
import com.android.util.Pair;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.ast.AstVisitor;
import lombok.ast.ClassDeclaration;
import lombok.ast.CompilationUnit;
import lombok.ast.Expression;
import lombok.ast.ForwardingAstVisitor;
import lombok.ast.MethodInvocation;
import lombok.ast.Select;
import lombok.ast.StrictListAccessor;
import lombok.ast.VariableReference;

/**
 * Check which looks for overdraw problems where view areas are painted and then
 * painted over, meaning that the bottom paint operation is a waste of time.
 */
public class OverdrawDetector extends LayoutDetector implements Detector.JavaScanner {
    private static final String R_STYLE_PREFIX = "R.style.";    //$NON-NLS-1$
    private static final String SET_THEME = "setTheme";         //$NON-NLS-1$

    /** The main issue discovered by this detector */
    public static final Issue ISSUE = Issue.create(
            "Overdraw", //$NON-NLS-1$
            "Looks for overdraw issues (where a view is painted only to be fully painted over)",
            "If you set a background drawable on a root view, then you should use a " +
            "custom theme where the theme background is null. Otherwise, the theme background " +
            "will be painted first, only to have your custom background completely cover it; " +
            "this is called \"overdraw\".\n" +
            "\n" +
            "NOTE: This detector relies on figuring out which layouts are associated with " +
            "which activities based on scanning the Java code, and it's currently doing that " +
            "using an inexact pattern matching algorithm. Therefore, it can incorrectly " +
            "conclude which activity the layout is associated with and then wrongly complain " +
            "that a background-theme is hidden.\n" +
            "\n" +
            "If you want your custom background on multiple pages, then you should consider " +
            "making a custom theme with your custom background and just using that theme " +
            "instead of a root element background.\n" +
            "\n" +
            "Of course it's possible that your custom drawable is translucent and you want " +
            "it to be mixed with the background. However, you will get better performance " +
            "if you pre-mix the background with your drawable and use that resulting image or " +
            "color as a custom theme background instead.\n",

            Category.PERFORMANCE,
            3,
            Severity.WARNING,
            OverdrawDetector.class,
            EnumSet.of(Scope.MANIFEST, Scope.JAVA_FILE, Scope.ALL_RESOURCE_FILES));

    /** Mapping from FQN activity names to theme names registered in the manifest */
    private Map<String, String> mActivityToTheme;

    /** The default theme declared in the manifest, or null */
    private String mManifestTheme;

    /** Mapping from layout name (not including {@code @layout/} prefix) to activity FQN */
    private Map<String, List<String>> mLayoutToActivity;

    /** List of theme names registered in the project which have blank backgrounds */
    private List<String> mBlankThemes;

    /** Set of activities registered in the manifest. We will limit the Java analysis to
     * these. */
    private Set<String> mActivities;

    /** List of drawable resources that are not flagged for overdraw (XML drawables
     * except for {@code <bitmap>} drawables without tiling) */
    private List<String> mValidDrawables;

    /**
     * List of pairs of (location, background drawable) corresponding to root elements
     * in layouts that define a given background drawable. These should be checked to
     * see if they are painting on top of a non-transparent theme.
     */
    private List<Pair<Location, String>> mRootAttributes;

    /** Constructs a new {@link OverdrawDetector} */
    public OverdrawDetector() {
    }

    @Override
    public boolean appliesTo(@NonNull ResourceFolderType folderType) {
        // Look in layouts for drawable resources
        return super.appliesTo(folderType)
                // and in resource files for theme definitions
                || folderType == ResourceFolderType.VALUES
                // and in drawable files for bitmap tiling modes
                || folderType == ResourceFolderType.DRAWABLE;
    }

    @Override
    public boolean appliesTo(@NonNull Context context, @NonNull File file) {
        return LintUtils.isXmlFile(file) || LintUtils.endsWith(file.getName(), DOT_JAVA);
    }

    @Override
    public @NonNull Speed getSpeed() {
        return Speed.FAST;
    }

    /** Is the given theme a "blank" theme (one not painting its background) */
    private boolean isBlankTheme(String name) {
        if (name.startsWith("@android:style/Theme_")) {               //$NON-NLS-1$
            if (name.contains("NoFrame")                              //$NON-NLS-1$
                    || name.contains("Theme_Wallpaper")               //$NON-NLS-1$
                    || name.contains("Theme_Holo_Wallpaper")          //$NON-NLS-1$
                    || name.contains("Theme_Translucent")             //$NON-NLS-1$
                    || name.contains("Theme_Dialog_NoFrame")          //$NON-NLS-1$
                    || name.contains("Theme_Holo_Dialog_Alert")       //$NON-NLS-1$
                    || name.contains("Theme_Holo_Light_Dialog_Alert") //$NON-NLS-1$
                    || name.contains("Theme_Dialog_Alert")            //$NON-NLS-1$
                    || name.contains("Theme_Panel")                   //$NON-NLS-1$
                    || name.contains("Theme_Light_Panel")             //$NON-NLS-1$
                    || name.contains("Theme_Holo_Panel")              //$NON-NLS-1$
                    || name.contains("Theme_Holo_Light_Panel")) {     //$NON-NLS-1$
                return true;
            }
        }

        if (mBlankThemes != null && mBlankThemes.contains(name)) {
            return true;
        }

        return false;
    }

    @Override
    public void afterCheckProject(@NonNull Context context) {
        if (mRootAttributes != null) {
            for (Pair<Location, String> pair : mRootAttributes) {
                Location location = pair.getFirst();

                Object clientData = location.getClientData();
                if (clientData instanceof Node) {
                    if (context.getDriver().isSuppressed(ISSUE, (Node) clientData)) {
                        return;
                    }
                }

                String layoutName = location.getFile().getName();
                if (endsWith(layoutName, DOT_XML)) {
                    layoutName = layoutName.substring(0, layoutName.length() - DOT_XML.length());
                }

                String theme = getTheme(context, layoutName);
                if (theme == null || !isBlankTheme(theme)) {
                    String drawable = pair.getSecond();
                    String message = String.format(
                            "Possible overdraw: Root element paints background %1$s with " +
                            "a theme that also paints a background (inferred theme is %2$s)",
                            drawable, theme);
                    // TODO: Compute applicable scope node
                    context.report(ISSUE, location, message, null);
                }

            }
        }
    }

    /** Return the theme to be used for the given layout */
    private String getTheme(Context context, String layoutName) {
        if (mActivityToTheme != null && mLayoutToActivity != null) {
            List<String> activities = mLayoutToActivity.get(layoutName);
            if (activities != null) {
                for (String activity : activities) {
                   String theme = mActivityToTheme.get(activity);
                    if (theme != null) {
                        return theme;
                    }
                }
            }
        }

        if (mManifestTheme != null) {
            return mManifestTheme;
        }

        Project project = context.getMainProject();
        int apiLevel = project.getTargetSdk();
        if (apiLevel == -1) {
            apiLevel = project.getMinSdk();
        }

        if (apiLevel >= 11) {
            return "@android:style/Theme.Holo"; //$NON-NLS-1$
        } else {
            return "@android:style/Theme"; //$NON-NLS-1$
        }
    }

    // ---- Implements XmlScanner ----

    @Override
    public void visitAttribute(@NonNull XmlContext context, @NonNull Attr attribute) {
        // Only consider the root element's background
        if (attribute.getOwnerDocument().getDocumentElement() == attribute.getOwnerElement()) {
            // If the drawable is a non-repeated pattern then the overdraw might be
            // intentional since the image isn't covering the whole screen
            String background = attribute.getValue();
            if (mValidDrawables != null && mValidDrawables.contains(background)) {
                return;
            }

            if (background.equals(TRANSPARENT_COLOR)) {
                return;
            }

            if (background.startsWith("@android:drawable/")) { //$NON-NLS-1$
                // We haven't had a chance to study the builtin drawables the way we
                // check the project local ones in scanBitmap() and beforeCheckFile(),
                // but many of these are not bitmaps, so ignore these
                return;
            }

            String name = context.file.getName();
            if (name.contains("list_") || name.contains("_item")) { //$NON-NLS-1$ //$NON-NLS-2$
                // Canonical list_item layout name: don't warn about these, it's
                // pretty common to want to paint custom list item backgrounds
                return;
            }

            Location location = context.getLocation(attribute);
            location.setClientData(attribute);
            if (mRootAttributes == null) {
                mRootAttributes = new ArrayList<Pair<Location,String>>();
            }
            mRootAttributes.add(Pair.of(location, attribute.getValue()));
        }
    }

    @Override
    public Collection<String> getApplicableAttributes() {
        return Collections.singletonList(
                // Layouts: Look for background attributes on root elements for possible overdraw
                ATTR_BACKGROUND
        );
    }

    @Override
    public Collection<String> getApplicableElements() {
        return Arrays.asList(
                // Manifest: Look at theme registrations
                TAG_ACTIVITY,
                TAG_APPLICATION,

                // Resource files: Look at theme definitions
                TAG_STYLE,

                // Bitmaps
                TAG_BITMAP
        );
    }

    @Override
    public void beforeCheckFile(@NonNull Context context) {
        if (endsWith(context.file.getName(), DOT_XML)) {
            // Drawable XML files should not be considered for overdraw, except for <bitmap>'s.
            // The bitmap elements are handled in the scanBitmap() method; it will clear
            // out anything added by this method.
            File parent = context.file.getParentFile();
            ResourceFolderType type = ResourceFolderType.getFolderType(parent.getName());
            if (type == ResourceFolderType.DRAWABLE) {
                if (mValidDrawables == null) {
                    mValidDrawables = new ArrayList<String>();
                }
                String resource = getDrawableResource(context.file);
                mValidDrawables.add(resource);
            }
        }
    }

    @Override
    public void visitElement(@NonNull XmlContext context, @NonNull Element element) {
        String tag = element.getTagName();
        if (tag.equals(TAG_STYLE)) {
            scanTheme(element);
        } else if (tag.equals(TAG_ACTIVITY)) {
            scanActivity(context, element);
        } else if (tag.equals(TAG_APPLICATION)) {
            if (element.hasAttributeNS(ANDROID_URI, ATTR_THEME)) {
                mManifestTheme = element.getAttributeNS(ANDROID_URI, ATTR_THEME);
            }
        } else if (tag.equals(TAG_BITMAP)) {
            scanBitmap(context, element);
        }
    }

    private String getDrawableResource(File drawableFile) {
        String resource = drawableFile.getName();
        if (endsWith(resource, DOT_XML)) {
            resource = resource.substring(0, resource.length() - DOT_XML.length());
        }
        return DRAWABLE_RESOURCE_PREFIX + resource;
    }

    private void scanBitmap(Context context, Element element) {
        String tileMode = element.getAttributeNS(ANDROID_URI, ATTR_TILE_MODE);
        if (!(tileMode.equals(VALUE_DISABLED) || tileMode.length() == 0)) {
            if (mValidDrawables != null) {
                String resource = getDrawableResource(context.file);
                mValidDrawables.remove(resource);
            }
        }
    }

    private void scanActivity(Context context, Element element) {
        String name = element.getAttributeNS(ANDROID_URI, ATTR_NAME);
        if (name.indexOf('$') != -1) {
            name = name.replace('$', '.');
        }
        if (name.startsWith(".")) {  //$NON-NLS-1$
            String pkg = context.getProject().getPackage();
            if (pkg != null && pkg.length() > 0) {
                name = pkg + name;
            }
        }

        if (mActivities == null) {
            mActivities = new HashSet<String>();
        }
        mActivities.add(name);

        String theme = element.getAttributeNS(ANDROID_URI, ATTR_THEME);
        if (theme != null && theme.length() > 0) {
            if (mActivityToTheme == null) {
                mActivityToTheme = new HashMap<String, String>();
            }
            mActivityToTheme.put(name, theme.replace('.', '_'));
        }
    }

    private void scanTheme(Element element) {
        // Look for theme definitions, and record themes that provide a null background.
        String styleName = element.getAttribute(ATTR_NAME);
        String parent = element.getAttribute(ATTR_PARENT);
        if (parent == null) {
            // Eclipse DOM workaround
            parent = "";
        }

        if (parent.length() == 0) {
            int index = styleName.lastIndexOf('.');
            if (index != -1) {
                parent = styleName.substring(0, index);
            }
        }
        parent = parent.replace('.', '_');

        String resource = STYLE_RESOURCE_PREFIX + styleName.replace('.', '_');

        NodeList items = element.getChildNodes();
        for (int i = 0, n = items.getLength(); i < n; i++) {
            if (items.item(i).getNodeType() == Node.ELEMENT_NODE) {
                Element item = (Element) items.item(i);
                String name = item.getAttribute(ATTR_NAME);
                if (name.equals("android:windowBackground")) {      //$NON-NLS-1$
                    NodeList textNodes = item.getChildNodes();
                    for (int j = 0, m = textNodes.getLength(); j < m; j++) {
                        Node textNode = textNodes.item(j);
                        if (textNode.getNodeType() == Node.TEXT_NODE) {
                            String text = textNode.getNodeValue();
                            String trim = text.trim();
                            if (trim.length() > 0) {
                                if (trim.equals(NULL_RESOURCE)
                                        || trim.equals(TRANSPARENT_COLOR)
                                        || mValidDrawables != null
                                            && mValidDrawables.contains(trim)) {
                                    if (mBlankThemes == null) {
                                        mBlankThemes = new ArrayList<String>();
                                    }
                                    mBlankThemes.add(resource);
                                }
                            }
                        }
                    }

                    return;
                }
            }
        }

        if (isBlankTheme(parent)) {
            if (mBlankThemes == null) {
                mBlankThemes = new ArrayList<String>();
            }
            mBlankThemes.add(resource);
            return;
        }
    }

    // ---- Implements JavaScanner ----

    @Override
    public List<Class<? extends lombok.ast.Node>> getApplicableNodeTypes() {
        // This detector does not specify specific node types; this means
        // that the infrastructure will run the full visitor on the compilation
        // unit rather than on individual nodes. This is important since this
        // detector relies on pruning (if it gets to a class declaration that is
        // not an activity, it skips everything inside).
        return null;
    }

    @Override
    public AstVisitor createJavaVisitor(@NonNull JavaContext context) {
        return new OverdrawVisitor();
    }

    private class OverdrawVisitor extends ForwardingAstVisitor {
        private static final String ACTIVITY = "Activity"; //$NON-NLS-1$
        private String mClassFqn;

        @Override
        public boolean visitClassDeclaration(ClassDeclaration node) {
            String name = node.getDescription();

            if (mActivities != null && mActivities.contains(mClassFqn) || name.endsWith(ACTIVITY)
                    || node.astExtending() != null &&
                        node.astExtending().getDescription().endsWith(ACTIVITY)) {
                String packageName = "";
                if (node.getParent() instanceof CompilationUnit) {
                    CompilationUnit compilationUnit = (CompilationUnit) node.getParent();
                    packageName = compilationUnit.astPackageDeclaration().getPackageName();
                }
                mClassFqn = (packageName.length() > 0 ? (packageName + '.') : "") + name;

                return false;
            }

            return true; // Done: No need to look inside this class
        }

        // Store R.layout references in activity classes in a map mapping back layouts
        // to activities
        @Override
        public boolean visitSelect(Select node) {
            if (node.astIdentifier().astValue().equals("layout") //$NON-NLS-1$
                    && node.astOperand() instanceof VariableReference
                    && ((VariableReference) node.astOperand()).astIdentifier().astValue()
                        .equals("R")                             //$NON-NLS-1$
                    && node.getParent() instanceof Select) {
                String layout = ((Select) node.getParent()).astIdentifier().astValue();
                if (mLayoutToActivity == null) {
                    mLayoutToActivity = new HashMap<String, List<String>>();
                }
                List<String> list = mLayoutToActivity.get(layout);
                if (list == null) {
                    list = new ArrayList<String>();
                    mLayoutToActivity.put(layout, list);
                }
                list.add(mClassFqn);
            }

            return false;
        }


        // Look for setTheme(R.style.whatever) and register as a theme registration
        // for the current activity
        @Override
        public boolean visitMethodInvocation(MethodInvocation node) {
            if (node.astName().astValue().equals(SET_THEME)) {
                // Look at argument
                StrictListAccessor<Expression, MethodInvocation> args = node.astArguments();
                if (args.size() == 1) {
                    Expression arg = args.first();
                    if (arg instanceof Select) {
                        String resource = arg.toString();
                        if (resource.startsWith(R_STYLE_PREFIX)) {
                            if (mActivityToTheme == null) {
                                mActivityToTheme = new HashMap<String, String>();
                            }
                            String name = ((Select) arg).astIdentifier().astValue();
                            mActivityToTheme.put(mClassFqn, STYLE_RESOURCE_PREFIX + name);
                        }
                    }
                }
            }

            return false;
        }
    }
}
