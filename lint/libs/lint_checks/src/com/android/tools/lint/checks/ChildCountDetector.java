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

import static com.android.tools.lint.detector.api.LintConstants.GRID_VIEW;
import static com.android.tools.lint.detector.api.LintConstants.HORIZONTAL_SCROLL_VIEW;
import static com.android.tools.lint.detector.api.LintConstants.LIST_VIEW;
import static com.android.tools.lint.detector.api.LintConstants.REQUEST_FOCUS;
import static com.android.tools.lint.detector.api.LintConstants.SCROLL_VIEW;

import com.android.annotations.NonNull;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.LayoutDetector;
import com.android.tools.lint.detector.api.LintUtils;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.Speed;
import com.android.tools.lint.detector.api.XmlContext;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.Arrays;
import java.util.Collection;

/**
 * Check which makes sure that views have the expected number of declared
 * children (e.g. at most one in ScrollViews and none in AdapterViews)
 */
public class ChildCountDetector extends LayoutDetector {

    /** The main issue discovered by this detector */
    public static final Issue SCROLLVIEW_ISSUE = Issue.create(
            "ScrollViewCount", //$NON-NLS-1$
            "Checks that ScrollViews have exactly one child widget",
            "ScrollViews can only have one child widget. If you want more children, wrap them " +
            "in a container layout.",
            Category.CORRECTNESS,
            8,
            Severity.WARNING,
            ChildCountDetector.class,
            Scope.RESOURCE_FILE_SCOPE);

    /** The main issue discovered by this detector */
    public static final Issue ADAPTERVIEW_ISSUE = Issue.create(
            "AdapterViewChildren", //$NON-NLS-1$
            "Checks that AdapterViews do not define their children in XML",
            "AdapterViews such as ListViews must be configured with data from Java code, " +
            "such as a ListAdapter.",
            Category.CORRECTNESS,
            10,
            Severity.WARNING,
            ChildCountDetector.class,
            Scope.RESOURCE_FILE_SCOPE).setMoreInfo(
                "http://developer.android.com/reference/android/widget/AdapterView.html"); //$NON-NLS-1$

    /** Constructs a new {@link ChildCountDetector} */
    public ChildCountDetector() {
    }

    @Override
    public @NonNull Speed getSpeed() {
        return Speed.FAST;
    }

    @Override
    public Collection<String> getApplicableElements() {
        return Arrays.asList(
                SCROLL_VIEW,
                HORIZONTAL_SCROLL_VIEW,
                LIST_VIEW,
                GRID_VIEW
                // TODO: Shouldn't Spinner be in this list too? (Was not there in layoutopt)
        );
    }

    @Override
    public void visitElement(@NonNull XmlContext context, @NonNull Element element) {
        int childCount = LintUtils.getChildCount(element);
        String tagName = element.getTagName();
        if (tagName.equals(SCROLL_VIEW) || tagName.equals(HORIZONTAL_SCROLL_VIEW)) {
            if (childCount > 1 && getAccurateChildCount(element) > 1) {
                context.report(SCROLLVIEW_ISSUE, element,
                        context.getLocation(element), "A scroll view can have only one child",
                        null);
            }
        } else {
            // Adapter view
            if (childCount > 0 && getAccurateChildCount(element) > 0) {
                context.report(ADAPTERVIEW_ISSUE, element,
                        context.getLocation(element),
                        "A list/grid should have no children declared in XML", null);
            }
        }
    }

    /** Counts the number of children, but skips certain tags like {@code <requestFocus>} */
    private static int getAccurateChildCount(Element element) {
        NodeList childNodes = element.getChildNodes();
        int childCount = 0;
        for (int i = 0, n = childNodes.getLength(); i < n; i++) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE &&
                    !REQUEST_FOCUS.equals(child.getNodeName())) {
                childCount++;
            }
        }

        return childCount;
    }
}
