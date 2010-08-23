/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.hierarchyviewerlib.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ViewNode {
    public static final String MISCELLANIOUS = "miscellaneous";

    public String id;

    public String name;

    public String hashCode;

    public List<Property> properties = new ArrayList<Property>();

    public Map<String, Property> namedProperties = new HashMap<String, Property>();

    public ViewNode parent;

    public List<ViewNode> children = new ArrayList<ViewNode>();

    public int left;

    public int top;

    public int width;

    public int height;

    public int scrollX;

    public int scrollY;

    public int paddingLeft;

    public int paddingRight;

    public int paddingTop;

    public int paddingBottom;

    public int marginLeft;

    public int marginRight;

    public int marginTop;

    public int marginBottom;

    public int baseline;

    public boolean willNotDraw;

    public boolean hasMargins;

    public boolean hasFocus;

    public int index;

    public double measureTime;

    public double layoutTime;

    public double drawTime;

    public Set<String> categories = new TreeSet<String>();

    public ViewNode(ViewNode parent, String data) {
        this.parent = parent;
        index = this.parent == null ? 0 : this.parent.children.size();
        if (this.parent != null) {
            this.parent.children.add(this);
        }
        int delimIndex = data.indexOf('@');
        name = data.substring(0, delimIndex);
        data = data.substring(delimIndex + 1);
        delimIndex = data.indexOf(' ');
        hashCode = data.substring(0, delimIndex);
        loadProperties(data.substring(delimIndex + 1).trim());

        measureTime = -1;
        layoutTime = -1;
        drawTime = -1;
    }

    private void loadProperties(String data) {
        int start = 0;
        boolean stop;
        do {
            int index = data.indexOf('=', start);
            ViewNode.Property property = new ViewNode.Property();
            property.name = data.substring(start, index);

            int index2 = data.indexOf(',', index + 1);
            int length = Integer.parseInt(data.substring(index + 1, index2));
            start = index2 + 1 + length;
            property.value = data.substring(index2 + 1, index2 + 1 + length);

            properties.add(property);
            namedProperties.put(property.name, property);

            stop = start >= data.length();
            if (!stop) {
                start += 1;
            }
        } while (!stop);

        Collections.sort(properties, new Comparator<ViewNode.Property>() {
            public int compare(ViewNode.Property source, ViewNode.Property destination) {
                return source.name.compareTo(destination.name);
            }
        });

        id = namedProperties.get("mID").value;

        left = namedProperties.containsKey("mLeft") ?
                getInt("mLeft", 0) : getInt("layout:mLeft", 0);
        top = namedProperties.containsKey("mTop") ?
                getInt("mTop", 0) : getInt("layout:mTop", 0);
        width = namedProperties.containsKey("getWidth()") ?
                getInt("getWidth()", 0) : getInt("measurement:getWidth()", 0);
        height = namedProperties.containsKey("getHeight()") ?
                getInt("getHeight()", 0) : getInt("measurement:getHeight()", 0);
        scrollX = namedProperties.containsKey("mScrollX") ?
                getInt("mScrollX", 0) : getInt("scrolling:mScrollX", 0);
        scrollY = namedProperties.containsKey("mScrollY") ?
                getInt("mScrollY", 0) : getInt("scrolling:mScrollY", 0);
        paddingLeft = namedProperties.containsKey("mPaddingLeft") ?
                getInt("mPaddingLeft", 0) : getInt("padding:mPaddingLeft", 0);
        paddingRight = namedProperties.containsKey("mPaddingRight") ?
                getInt("mPaddingRight", 0) : getInt("padding:mPaddingRight", 0);
        paddingTop = namedProperties.containsKey("mPaddingTop") ?
                getInt("mPaddingTop", 0) : getInt("padding:mPaddingTop", 0);
        paddingBottom = namedProperties.containsKey("mPaddingBottom") ?
                getInt("mPaddingBottom", 0) : getInt("padding:mPaddingBottom", 0);
        marginLeft = namedProperties.containsKey("layout_leftMargin") ?
                getInt("layout_leftMargin", Integer.MIN_VALUE) :
                getInt("layout:leftMargin", Integer.MIN_VALUE);
        marginRight = namedProperties.containsKey("layout_rightMargin") ?
                getInt("layout_rightMargin", Integer.MIN_VALUE) :
                getInt("layout:rightMargin", Integer.MIN_VALUE);
        marginTop = namedProperties.containsKey("layout_topMargin") ?
                getInt("layout_topMargin", Integer.MIN_VALUE) :
                getInt("layout:topMargin", Integer.MIN_VALUE);
        marginBottom = namedProperties.containsKey("layout_bottomMargin") ?
                getInt("layout_bottomMargin", Integer.MIN_VALUE) :
                getInt("layout:bottomMargin", Integer.MIN_VALUE);
        baseline = namedProperties.containsKey("getBaseline()") ?
                getInt("getBaseline()", 0) :
                getInt("measurement:getBaseline()", 0);
        willNotDraw = namedProperties.containsKey("willNotDraw()") ?
                getBoolean("willNotDraw()", false) :
                getBoolean("drawing:willNotDraw()", false);
        hasFocus = namedProperties.containsKey("hasFocus()") ?
                getBoolean("hasFocus()", false) :
                getBoolean("focus:hasFocus()", false);

        hasMargins =
                marginLeft != Integer.MIN_VALUE && marginRight != Integer.MIN_VALUE
                        && marginTop != Integer.MIN_VALUE && marginBottom != Integer.MIN_VALUE;

        for(String name : namedProperties.keySet()) {
            int index = name.indexOf(':');
            if(index != -1) {
                categories.add(name.substring(0, index));
            }
        }
        if(categories.size() != 0) {
            categories.add(MISCELLANIOUS);
        }
    }

    private boolean getBoolean(String name, boolean defaultValue) {
        Property p = namedProperties.get(name);
        if (p != null) {
            try {
                return Boolean.parseBoolean(p.value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int getInt(String name, int defaultValue) {
        Property p = namedProperties.get(name);
        if (p != null) {
            try {
                return Integer.parseInt(p.value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return name + "@" + hashCode;
    }

    public static class Property {
        public String name;

        public String value;

        @Override
        public String toString() {
            return name + '=' + value;
        }
    }
}